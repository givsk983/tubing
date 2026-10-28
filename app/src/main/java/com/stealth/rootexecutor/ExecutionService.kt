package com.stealth.rootexecutor

import android.app.*
import android.content.Intent
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

object SessionState {
    val startGate = ExecutionStartGate()
    private val main = Handler(Looper.getMainLooper())
    private val history = StringBuilder()
    private val observers = linkedSetOf<(String) -> Unit>()
    private val pending = StringBuilder()
    private var scheduled = false
    private val flush = Runnable {
        val batch: Pair<String, List<(String) -> Unit>>
        synchronized(this) {
            batch = pending.toString() to observers.toList()
            pending.clear(); scheduled = false
        }
        batch.second.forEach { it(batch.first) }
    }
    @Volatile var running = false
    @Volatile var status = "终端已就绪"
    @Volatile var title = "交互终端"
    @Volatile var executionId: String? = null
    @Volatile var kind = "library"
    @Volatile var rememberInput = false
    @Volatile var trackInputHistory = true
    @Volatile var mode = ExecutionMode.PIPE
    @Volatile var exitCode: Int? = null
    @Volatile var cols = 80
    @Volatile var rows = 24
    @Synchronized fun output(text: String) {
        history.append(text)
        if (history.length > 512_000) history.delete(0, history.length - 400_000)
        pending.append(text)
        if (pending.length > 512_000) pending.delete(0, pending.length - 400_000)
        if (!scheduled) { scheduled = true; main.postDelayed(flush, 32) }
    }
    @Synchronized fun subscribe(listener: (String) -> Unit) {
        if (scheduled) { main.removeCallbacks(flush); flush.run() }
        observers.add(listener); listener(history.toString())
    }
    @Synchronized fun unsubscribe(listener: (String) -> Unit) { observers.remove(listener) }
    @Synchronized fun clearOutput() {
        history.clear(); pending.clear(); main.removeCallbacks(flush); scheduled = false
    }
    @Synchronized fun clear() { if (!running) clearOutput() }
}

class ExecutionService : Service() {
    companion object {
        @Volatile var active: ExecutionService? = null
        const val STOP = "停止执行"
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val input = Executors.newSingleThreadExecutor()
    @Volatile private var session: ExecutionSession? = null
    val sessionReady: Boolean get() = session?.inputReady == true && SessionState.running
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWake = object : Runnable {
        override fun run() {
            if (SessionState.running) { wake?.acquire(10 * 60_000L); main.postDelayed(this, 5 * 60_000L) }
        }
    }
    private val root = RootExecutor()
    private val prompts = PromptBuffer()
    private var expectedPrompts: List<String> = emptyList()
    private var sendMode = InputSendMode.DELAY
    @Volatile private var failureReason: String? = null
    private var watchdog: Runnable? = null
    @Volatile private var deliveredInputs = 0
    private val sequenceProgress = Any()
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); active = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { terminate(); return START_NOT_STICKY }
        if (SessionState.running || intent == null) return START_NOT_STICKY
        val kind = intent.getStringExtra("kind") ?: return START_NOT_STICKY
        val name = intent.getStringExtra("name") ?: return START_NOT_STICKY
        QuickExecutionExit.cancel()
        val quickExecution = intent.getBooleanExtra(QuickExecutionExit.SHORTCUT, false)
        val inputSteps = intent.getStringArrayListExtra("inputSteps")?.toList() ?: emptyList()
        expectedPrompts = intent.getStringArrayListExtra("inputPrompts")?.toList() ?: emptyList()
        sendMode = InputSendMode.fromKey(intent.getStringExtra("inputSendMode"))
        val sensitiveValues = intent.getStringArrayListExtra("sensitiveValues")?.toList() ?: emptyList()
        val automationPackage = intent.getStringExtra("automationPackage")
        val automationShortcut = intent.getStringExtra("automationShortcut") ?: ""
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("privacyDefaultsV1", false)) prefs.edit().putBoolean("saveLogs", false).putBoolean("privacyDefaultsV1", true).commit()
        val timeoutSeconds = intent.getIntExtra("timeoutSeconds", prefs.getInt("executionTimeoutSeconds", 120)).coerceIn(5, 3600)
        val saveOutput = prefs.getBoolean("saveLogs", false) && sensitiveValues.isEmpty()
        val redactor = SecretRedactor(sensitiveValues)
        val mode = ExecutionMode.fromKey(intent.getStringExtra("mode") ?: getSharedPreferences("settings", MODE_PRIVATE).getString("executionMode", "pipe"))
        SessionState.clear()
        SessionState.running = true
        SessionState.title = name
        SessionState.mode = mode
        SessionState.exitCode = null
        SessionState.rememberInput = intent.getBooleanExtra("rememberInput", false)
        SessionState.trackInputHistory = intent.getBooleanExtra("trackInputHistory", true)
        SessionState.status = "正在运行 · ${mode.label}"
        stopping = false
        failureReason = null
        prompts.clear()
        deliveredInputs = 0
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("execution", "脚本执行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ExecutionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "execution").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Tubing · ${mode.label}执行中").setContentText(name).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "终止执行", stop).build()).build()
        startForeground(7, notification)
        val lease = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tubing:execution").apply { setReferenceCounted(false) }
        wake = lease
        val token = UUID.randomUUID().toString()
        SessionState.executionId = token
        SessionState.kind = kind
        ExecutionHistory.start(this, ExecutionRecord(token, kind, name, mode.key, System.currentTimeMillis()))
        watchdog = Runnable { if (SessionState.executionId == token && SessionState.running) failSession("任务超过 ${timeoutSeconds} 秒，已终止") }
        main.postDelayed(watchdog!!, timeoutSeconds * 1000L)
        executor.execute {
            val log = File(cacheDir, "$token.log")
            var writer: java.io.BufferedWriter? = null
            try {
                check(sendMode.valid(inputSteps, expectedPrompts)) {
                    "输入方案配置不完整，请补齐提示词或改用延时发送；未启动脚本"
                }
                cacheDir.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]+\\.log")) && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                if (saveOutput) writer = log.bufferedWriter()
                var logSize = 0
                val outputLock = Any()
                val display: (String) -> Unit = { text -> synchronized(outputLock) {
                    val safe = redactor.accept(text)
                    SessionState.output(safe)
                    if (logSize < 131_072) {
                        val chunk = safe.take(131_072 - logSize)
                        writer?.write(chunk); writer?.flush(); logSize += chunk.length
                    }
                } }
                val callback: (String) -> Unit = { text -> prompts.append(text); display(text) }
                lease.acquire(10 * 60_000L)
                main.postDelayed(renewWake, 5 * 60_000L)
                val terminal: ExecutionSession = when (mode) {
                    ExecutionMode.PIPE -> PipeSession(root, token).apply { onInputEcho = display }
                    ExecutionMode.PTY -> PtySession(root, token)
                }
                session = terminal
                if (stopping) error("执行已取消")
                callback("\u001b[36m[Tubing] 执行方式：${mode.label}\u001b[0m\r\n")
                if (mode == ExecutionMode.PIPE) callback("[输入提示] 输入后按回车提交；方向键菜单请选择 PTY。\r\n")
                if (automationPackage != null) {
                    AppAutomation.requireForeground(this, automationPackage, automationShortcut, intent.getIntExtra("profileUser", 0))
                    val blocked = AppAutomation.blocked(prefs, automationPackage)
                    check(blocked == null) { blocked ?: "冷却中" }
                    if (stopping) error("执行已取消")
                    AppAutomation.markAttempt(prefs, automationPackage)
                }
                if (stopping) error("执行已取消")
                AppAutomation.status(this, "脚本已启动")
                val code = terminal.run(ManagedFile(kind, name, ""), callback)
                SessionState.exitCode = code
                if (inputSteps.isNotEmpty() && synchronized(sequenceProgress) { deliveredInputs < inputSteps.size } && !stopping)
                    failureReason = "脚本结束前输入方案未完成"
                SessionState.status = failureReason ?: if (stopping) "已终止" else "已结束 · 退出码 $code"
                callback("\r\n[Tubing] ${SessionState.status}\r\n")
            } catch (e: Throwable) {
                SessionState.exitCode = null
                val detail = if (sensitiveValues.isEmpty()) e.message?.take(160) else "敏感任务未完成，请检查配置、提示词或权限"
                if (!stopping) failureReason = failureReason ?: detail ?: "执行失败"
                SessionState.status = failureReason?.let { "执行失败 · $it" } ?: "已终止"
                val error = "\r\n${SessionState.status}\r\n"
                SessionState.output(error)
                runCatching { writer?.write(error) }
            } finally {
                stopping = true
                watchdog?.let { main.removeCallbacks(it) }; watchdog = null
                val tail = redactor.finish()
                if (tail.isNotEmpty()) SessionState.output(tail)
                runCatching { writer?.close() }
                prompts.clear()
                ExecutionHistory.finish(this, token, SessionState.status, SessionState.exitCode)
                AppAutomation.status(this, failureReason ?: SessionState.status,
                    failed = failureReason != null || (SessionState.exitCode != null && SessionState.exitCode != 0))
                main.removeCallbacks(renewWake)
                if (lease.isHeld) lease.release()
                try { session?.stop() } catch (_: Exception) { }
                if (saveOutput && log.exists()) {
                    try {
                        val saved = root.saveSession(log, token)
                        if (saved.code != 0) SessionState.output("\r\n日志保存失败：${saved.output}\r\n")
                    } catch (e: Exception) { SessionState.output("\r\n日志保存失败：${e.message}\r\n") }
                }
                log.delete()
                runCatching { root.pruneLogs() }
                session = null
                Handler(mainLooper).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    QuickExecutionExit.complete(this, token, quickExecution)
                    SessionState.running = false
                    SessionState.output("")
                    stopSelf()
                }
            }
        }
        if (inputSteps.isNotEmpty()) sendSequence(inputSteps)
        return START_NOT_STICKY
    }
    fun send(text: String) {
        if (!SessionState.running || input.isShutdown) return
        input.execute { try { session?.send(text) } catch (e: Exception) { SessionState.output("\r\n输入失败：${e.message}\r\n") } }
    }
    fun sendSequence(values: List<String>) {
        if (!SessionState.running || input.isShutdown || values.isEmpty()) return
        input.execute {
            try {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (SessionState.running && !stopping && !sessionReady && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                }
                if (!SessionState.running || stopping) return@execute
                check(sessionReady) { "等待脚本输入通道超时" }
                check(sendMode.valid(values, expectedPrompts)) { "方案配置不完整" }
                SessionState.output("\r\n[快捷方式] 已加载 ${values.size} 项输入 · ${sendMode.label}\r\n")
                if (sendMode == InputSendMode.DELAY) Thread.sleep(InputSendMode.INITIAL_DELAY_MS)
                values.forEachIndexed { index, value ->
                    if (sendMode == InputSendMode.PROMPT) {
                        val promptDeadline = SystemClock.elapsedRealtime() + 30_000
                        while (SessionState.running && !stopping && !prompts.consume(expectedPrompts[index])) {
                            check(SystemClock.elapsedRealtime() < promptDeadline) { "等待第 ${index + 1} 项提示超时，已停止任务" }
                            Thread.sleep(50)
                        }
                    } else if (index > 0) {
                        Thread.sleep(InputSendMode.BETWEEN_INPUTS_MS)
                    }
                    if (!SessionState.running || stopping) return@execute
                    val current = session
                    if (current?.inputReady != true) {
                        failSession("输入通道已关闭，已停止任务")
                        return@execute
                    }
                    synchronized(sequenceProgress) {
                        if (stopping) return@execute
                        current.send(value + "\r")
                        deliveredInputs = index + 1
                    }
                    SessionState.output("\r\n[快捷方式] 已提交输入 ${index + 1}/${values.size}\r\n")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                failSession(e.message ?: "输入方案发送失败")
            }
        }
    }
    fun resize(cols: Int, rows: Int) { session?.resize(cols, rows) }
    private fun failSession(reason: String) {
        if (!SessionState.running || stopping) return
        failureReason = reason
        SessionState.output("\r\n[Tubing] $reason\r\n")
        AppAutomation.status(this, reason, failed = true)
        terminate()
    }
    fun terminate() {
        if (stopping || !SessionState.running) return
        stopping = true
        SessionState.status = "正在终止"
        Thread { session?.stop() }.start()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { terminate(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        terminate()
        main.removeCallbacks(renewWake)
        watchdog?.let { main.removeCallbacks(it) }
        if (active === this) active = null
        wake?.let { if (it.isHeld) it.release() }
        executor.shutdown(); input.shutdownNow()
        super.onDestroy()
        QuickExecutionExit.afterServiceDestroyed(this)
    }
}

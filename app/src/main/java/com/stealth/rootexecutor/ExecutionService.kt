package com.stealth.rootexecutor

import android.app.*
import android.content.Intent
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

object SessionState {
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
    @Synchronized fun clear() { if (!running) { history.clear(); pending.clear(); main.removeCallbacks(flush); scheduled = false } }
}

class ExecutionService : Service() {
    companion object {
        @Volatile var active: ExecutionService? = null
        const val STOP = "停止执行"
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val input = Executors.newSingleThreadExecutor()
    @Volatile private var session: PtySession? = null
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWake = object : Runnable {
        override fun run() {
            if (SessionState.running) { wake?.acquire(10 * 60_000L); main.postDelayed(this, 5 * 60_000L) }
        }
    }
    private val root = RootExecutor()
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); active = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { terminate(); return START_NOT_STICKY }
        if (SessionState.running || intent == null) return START_NOT_STICKY
        val kind = intent.getStringExtra("kind") ?: return START_NOT_STICKY
        val name = intent.getStringExtra("name") ?: return START_NOT_STICKY
        SessionState.clear()
        SessionState.running = true
        SessionState.title = name
        SessionState.status = "正在运行"
        stopping = false
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("execution", "脚本执行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ExecutionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "execution").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("tubing · 正在执行").setContentText(name).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "终止执行", stop).build()).build()
        startForeground(7, notification)
        val lease = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tubing:execution").apply { setReferenceCounted(false) }
        wake = lease
        val token = UUID.randomUUID().toString()
        executor.execute {
            val log = File(cacheDir, "$token.log")
            try {
                lease.acquire(10 * 60_000L)
                main.postDelayed(renewWake, 5 * 60_000L)
                val terminal = PtySession(root, token)
                session = terminal
                log.bufferedWriter().use { writer ->
                    val callback: (String) -> Unit = { text -> synchronized(writer) { writer.write(text); writer.flush() }; SessionState.output(text) }
                    if (stopping) error("执行已取消")
                    callback("\u001b[36m[tubing] 已连接交互终端\u001b[0m\r\n")
                    val code = terminal.run(ManagedFile(kind, name, ""), callback)
                    SessionState.status = if (stopping) "已终止" else "已结束 · 退出码 $code"
                    callback("\r\n[tubing] ${SessionState.status}\r\n")
                }
            } catch (e: Throwable) {
                SessionState.status = if (stopping) "已终止" else "执行失败"
                val error = "\r\n${SessionState.status}：${e.message}\r\n"
                SessionState.output(error)
                runCatching { log.appendText(error) }
            } finally {
                main.removeCallbacks(renewWake)
                if (lease.isHeld) lease.release()
                try { session?.stop() } catch (_: Exception) { }
                if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("saveLogs", true)) {
                    try {
                        val saved = root.saveSession(log, token)
                        if (saved.code != 0) SessionState.output("\r\n日志保存失败：${saved.output}\r\n")
                    } catch (e: Exception) { SessionState.output("\r\n日志保存失败：${e.message}\r\n") }
                }
                log.delete()
                session = null
                Handler(mainLooper).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    SessionState.running = false
                    SessionState.output("")
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
    fun send(text: String) { input.execute { session?.send(text) } }
    fun resize(cols: Int, rows: Int) { session?.resize(cols, rows) }
    fun terminate() {
        if (stopping) return
        stopping = true
        SessionState.status = "正在终止"
        Thread { session?.stop() }.start()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { terminate(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        terminate()
        main.removeCallbacks(renewWake)
        if (active === this) active = null
        wake?.let { if (it.isHeld) it.release() }
        executor.shutdown(); input.shutdown()
        super.onDestroy()
    }
}

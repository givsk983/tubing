package com.stealth.rootexecutor

import android.app.Activity
import android.app.Instrumentation
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Device-only checks against the actual Android ZIP, root shell and execution backends. */
class FeatureSmokeInstrumentation : Instrumentation() {
    private var cleanupFixtureId: String? = null
    private var updatesOnly = false
    private var scrollingOnly = false
    private var browserPerformanceOnly = false
    private var automationDiagnosticsOnly = false
    private var automationRepairOnly = false
    private var automationSafetyOnly = false
    private var automationRecoveryOnly = false
    private var moduleBundleOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cleanupFixtureId = arguments?.getString("cleanup_fixture_id")
        updatesOnly = arguments?.getString("updates_only") == "true"
        scrollingOnly = arguments?.getString("scrolling_only") == "true"
        browserPerformanceOnly = arguments?.getString("browser_performance_only") == "true"
        automationDiagnosticsOnly = arguments?.getString("automation_diagnostics_only") == "true"
        automationRepairOnly = arguments?.getString("automation_repair_only") == "true"
        automationSafetyOnly = arguments?.getString("automation_safety_only") == "true"
        automationRecoveryOnly = arguments?.getString("automation_recovery_only") == "true"
        moduleBundleOnly = arguments?.getString("module_bundle_only") == "true"
        start()
    }

    override fun onStart() {
        if (moduleBundleOnly) {
            try {
                val bundle = BundledModule.read(targetContext)
                val entries = linkedSetOf<String>()
                java.util.zip.ZipInputStream(bundle.bytes.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        check('/' !in entry.name) { "模块 ZIP 多了一层目录" }
                        entries.add(entry.name)
                        if (entry.name.endsWith(".sh") || entry.name == "module.prop") check('\r' !in zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
                check(entries.containsAll(listOf("module.prop", "customize.sh", "service.sh", "supervisor.sh", "monitor.sh", "detection.sh", "action.sh", "skip_mount")))
                val applications = targetContext.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName }.toSet()
                check(applications.size > 1) { "应用查询范围不正确" }
                val report = StringBuilder("Bundled module ${bundle.version}: ${entries.size} root entries, LF verified; ${applications.size} visible launcher apps\n")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val uri = BundledModule.exportDownloads(targetContext, bundle)
                    check(targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(bundle.bytes))
                    check(BundledModule.exportDownloads(targetContext, bundle) == uri) { "重复导出不应创建重复安装包" }
                    report.append("Download export byte match and URI reuse verified: $uri\n")
                    for (pkg in listOf("com.topjohnwu.magisk", "me.weishu.kernelsu")) {
                        val intent = BundledModule.managerIntent(targetContext, pkg, uri) ?: continue
                        val resolved = targetContext.packageManager.resolveActivity(intent, 0)
                        check(resolved?.activityInfo?.packageName == pkg)
                        if (intent.action == Intent.ACTION_VIEW) check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && intent.clipData != null)
                        report.append("Manager handoff resolved: $pkg -> ${resolved?.activityInfo?.name}; not launched\n")
                    }
                }
                finish(Activity.RESULT_OK, Bundle().apply { putString("module_bundle", report.toString()) })
            } catch (e: Throwable) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("module_bundle_error", e.stackTraceToString()) })
            }
            return
        }
        if (automationRecoveryOnly) {
            try { finish(Activity.RESULT_OK, Bundle().apply { putString("automation_recovery", AutomationSafetyChecks.monitorRecovery()) }) }
            catch (e: Throwable) { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("automation_recovery_error", e.message) }) }
            return
        }
        if (automationSafetyOnly) {
            try {
                val checks = AutomationSafetyChecks.run(targetContext)
                finish(Activity.RESULT_OK, Bundle().apply { putString("automation_safety", checks.joinToString("\n")) })
            } catch (e: Throwable) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("automation_safety_error", e.stackTraceToString()) })
            }
            return
        }
        if (automationRepairOnly) {
            repairAutomationModule()
            return
        }
        if (automationDiagnosticsOnly) {
            val report = runCatching { RootExecutor().run("""
                id
                echo MODULE
                cat /data/adb/modules/tubing_app_trigger/module.prop
                cat /data/adb/modules/tubing_app_trigger/status.log
                cat /data/adb/modules/tubing_app_trigger/last-trigger.log
                cat /data/adb/modules/tubing_app_trigger/monitor-output.log
                echo USER_QUERY
                /system/bin/am get-current-user
                echo USER_QUERY_REDIRECTED
                /system/bin/am get-current-user </dev/null 2>&1
                echo CMD_QUERY
                /system/bin/cmd activity get-current-user </dev/null 2>&1
                echo MODULE_STDIN_REPRO
                /system/bin/sh -c '
                  exec 0>/data/adb/modules/tubing_app_trigger/diagnostic.lock
                  echo ORIGINAL
                  /system/bin/am get-current-user 2>&1
                  echo RESULT=${'$'}?
                  echo DEVNULL
                  /system/bin/am get-current-user </dev/null 2>&1
                  echo RESULT=${'$'}?
                  rm /data/adb/modules/tubing_app_trigger/diagnostic.lock
                '
                echo SELINUX
                cat /proc/self/attr/current
                echo SYSTEM_STATE
                /system/bin/dumpsys power | grep mWakefulness
                /system/bin/dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'
                echo MONITOR_CONTEXT
                for p in ${'$'}(pidof sh); do
                  tr '\000' ' ' < /proc/${'$'}p/cmdline | grep -q '/data/adb/modules/tubing_app_trigger/monitor.sh' || continue
                  echo PID=${'$'}p
                  cat /proc/${'$'}p/attr/current
                  tr '\000' '\n' < /proc/${'$'}p/environ | grep -E '^(PATH|CLASSPATH|ANDROID_ROOT|ANDROID_DATA|ASH_STANDALONE|LD_LIBRARY_PATH|ANDROID_RUNTIME_ROOT|ANDROID_TZDATA_ROOT|ANDROID_ART_ROOT|ANDROID_I18N_ROOT)='
                  ls -l /proc/${'$'}p/fd/0 /proc/${'$'}p/fd/1 /proc/${'$'}p/fd/2
                done
            """.trimIndent()).output }.getOrElse { "Diagnostics failed: ${it.message}" }
            finish(Activity.RESULT_OK, Bundle().apply { putString("automation_diagnostics", report) })
            return
        }
        if (updatesOnly) { checkUpdates(); return }
        if (scrollingOnly) { checkBrowserScrolling(); return }
        if (browserPerformanceOnly) { checkBrowserPerformance(); return }
        cleanupFixtureId?.let { cleanupPreviousFixture(it); return }
        val root = RootExecutor()
        val id = UUID.randomUUID().toString()
        val fixture = File(targetContext.cacheDir, "smoke-$id").apply { mkdirs() }
        val imported = mutableListOf<ManagedFile>()
        val tokens = mutableListOf<String>()
        val createdLegacyDirectories = mutableListOf<String>()
        val projects = mutableListOf<String>()
        val checks = mutableListOf<String>()
        var archive: File? = null
        var failure: Throwable? = null
        try {
            check(root.initialize().code == 0) { "Root authorization failed" }
            val browserScript = File(fixture, "browser-$id.SH").apply { writeText("echo browser-import") }
            val browserResult = root.importBrowserSource(browserScript.absolutePath, browserScript.name)
            check(browserResult.code == 0) { browserResult.output }
            val browserFields = browserResult.output.lineSequence().first { it.startsWith("IMPORTED|") }.split('|')
            imported.add(ManagedFile(browserFields[1], browserFields[2], "777"))
            check(browserFields[1] == "library")
            check(root.run("cat ${root.quote("${root.managedRoot}/${browserFields[2]}")}").output.trim() == "echo browser-import")
            // A manually copied program must be listed without going through the importer.
            check(root.copyPath(browserScript.absolutePath, root.managedRoot).code == 0)
            val manualFile = ManagedFile("library", browserScript.name, "777").also { imported.add(it) }
            check(root.list().second.any { it.kind == manualFile.kind && it.name == manualFile.name })
            checks.add("Unified library: import and manually copied files are listed at root")
            val invalidImport = File(fixture, "not-executable.txt").apply { writeText("hello") }
            check(runCatching { root.importBrowserSource(invalidImport.absolutePath, invalidImport.name) }.isFailure)
            check(root.importBrowserSource(fixture.absolutePath, "directory.sh").code != 0)
            checks.add("File-browser import: classify SH, preserve contents, reject non-executable files and directories")
            val zip = File(fixture, "中文.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                listOf("中文/run.sh" to "echo hello", "folder/nested.txt" to "nested", "skip.txt" to "skip").forEach { (name, value) ->
                    output.putNextEntry(ZipEntry(name)); output.write(value.toByteArray()); output.closeEntry()
                }
            }
            val snapshot = root.prepareZip(zip.absolutePath, fixture)
            archive = snapshot
            val items = ZipExtractor.inspect(snapshot)
            check(items.any { it.path == "中文" && it.directory })
            check(ZipExtractor.preview(snapshot, items.first { it.path == "中文/run.sh" }) == "echo hello")
            checks.add("Android ZIP inspection and Chinese text preview")
            val destination = File(fixture, "destination").apply { mkdir() }
            root.extractZipSelection(snapshot, destination.absolutePath, setOf("中文/run.sh", "folder"))
            check(root.run("cat ${root.quote(File(destination, "run.sh").absolutePath)}").output.trim() == "echo hello")
            check(root.run("cat ${root.quote(File(destination, "folder/nested.txt").absolutePath)}").output.trim() == "nested")
            check(!File(destination, "skip.txt").exists())
            checks.add("Root publication of selected file and folder")
            check(runCatching { root.extractZipSelection(snapshot, destination.absolutePath, setOf("中文/run.sh")) }.isFailure)
            check(root.run("cat ${root.quote(File(destination, "run.sh").absolutePath)}").output.trim() == "echo hello")
            check(destination.list().orEmpty().none { it.startsWith(".tubing-extract-") })
            checks.add("Existing target is preserved and staging is removed")

            val projectZip = File(fixture, "完整项目.zip")
            ZipOutputStream(projectZip.outputStream()).use { output ->
                listOf("bin/run (2).SH" to "#!/system/bin/sh\ncat '../assets/中文配置.txt'\n", "assets/中文配置.txt" to "PROJECT_RESOURCE_OK").forEach { (name, value) ->
                    output.putNextEntry(ZipEntry(name)); output.write(value.toByteArray()); output.closeEntry()
                }
            }
            val projectName = "项目-$id"
            projects.add("${root.managedRoot}/$projectName")
            root.importZipProject(projectZip, projectName)
            val projectFile = ManagedFile("project", "$projectName/bin/run (2).SH", "755")
            check(root.list().second.any { it.kind == projectFile.kind && it.name == projectFile.name })
            check(root.run("cat ${root.quote("${root.managedRoot}/$projectName/assets/中文配置.txt")}").output.trim() == "PROJECT_RESOURCE_OK")
            check(runCatching { root.importZipProject(projectZip, projectName) }.isFailure)
            check(runCatching { root.importZipProject(projectZip, "tmp") }.isFailure)
            for (pty in listOf(false, true)) {
                val token = UUID.randomUUID().toString().also { tokens.add(it) }
                val session: ExecutionSession = if (pty) PtySession(root, token) else PipeSession(root, token)
                val output = StringBuilder()
                check(session.run(projectFile) { output.append(it) } == 0)
                check(output.contains("PROJECT_RESOURCE_OK")) { output.toString() }
            }
            checks.add("ZIP project: preserve tree and resources, reject duplicate/system names, list and run nested programs with relative resources in Pipe and PTY")

            val direct = File(fixture, "direct.sh").apply { writeText("#!/system/bin/sh\ncat './direct-resource.txt'\n") }
            File(fixture, "direct-resource.txt").writeText("DIRECT_EXECUTION_OK")
            val directFile = root.browserProgram(direct.absolutePath)
            check(directFile.kind == "external" && root.filePath(directFile) == direct.canonicalPath)
            for (pty in listOf(false, true)) {
                val token = UUID.randomUUID().toString().also { tokens.add(it) }
                val session: ExecutionSession = if (pty) PtySession(root, token) else PipeSession(root, token)
                val output = StringBuilder()
                val code = session.run(directFile) { output.append(it) }
                check(code == 0) { "Direct execution failed ($code): $output" }
                check(output.contains("DIRECT_EXECUTION_OK")) { output.toString() }
            }
            check(runCatching { root.browserProgram(File(fixture, "direct-resource.txt").absolutePath) }.isFailure)
            checks.add("File manager direct execution: validate SH/ELF and preserve original directory and resources in Pipe and PTY")

            for (kind in listOf("scripts", "binaries")) {
                val source = if (kind == "scripts") File(fixture, "permission.sh").apply {
                    writeText("#!/system/bin/sh\nprintf 'MODE:'\nstat -c %a \"\$0\"\n")
                }.absolutePath else root.run("if [ -e /system/bin/linker64 ]; then readlink -f /system/bin/linker64; else readlink -f /system/bin/linker; fi").output.trim()
                val result = root.importSource(source, "permission-$id" + if (kind == "scripts") ".sh" else "", kind)
                check(result.code == 0) { result.output }
                val fields = result.output.lineSequence().first { it.startsWith("IMPORTED|") }.split('|')
                val file = ManagedFile(fields[1], fields[2], "600")
                imported.add(file)
                check(file.kind == "library")
                if (kind == "binaries") {
                    val autoResult = root.importBrowserSource(source, "browser-elf-$id.sh")
                    check(autoResult.code == 0) { autoResult.output }
                    val autoFields = autoResult.output.lineSequence().first { it.startsWith("IMPORTED|") }.split('|')
                    imported.add(ManagedFile(autoFields[1], autoFields[2], "777"))
                    check(autoFields[1] == "library")
                    checks.add("File-browser import: ELF header overrides misleading .sh extension")
                }
                val path = root.quote(root.filePath(file))
                for (pty in listOf(false, true)) {
                    check(root.run("chmod 600 $path").code == 0)
                    val token = UUID.randomUUID().toString().also { tokens.add(it) }
                    val session: ExecutionSession = if (pty) PtySession(root, token) else PipeSession(root, token)
                    val output = StringBuilder()
                    val code = session.run(file) { output.append(it) }
                    check(code == 0) { "Execution failed ($kind, pty=$pty): $code\n$output" }
                    check(root.run("stat -c %a $path").output.trim() == "777")
                    if (kind == "scripts") check(output.contains("MODE:777")) { output.toString() }
                    checks.add("$kind ${if (pty) "PTY" else "Pipe"}: 600 -> 777 before execution")
                }
                val legacyDirectory = "${root.managedRoot}/$kind"
                val legacySetup = root.run("if [ -d ${root.quote(legacyDirectory)} ]; then echo existing; else mkdir -- ${root.quote(legacyDirectory)} && echo created; fi")
                check(legacySetup.code == 0) { legacySetup.output }
                if (legacySetup.output.trim() == "created") createdLegacyDirectories.add(legacyDirectory)
                val legacy = ManagedFile(kind, "legacy-$id" + if (kind == "scripts") ".sh" else "", "600")
                val legacyPath = root.quote(root.filePath(legacy))
                imported.add(legacy)
                check(root.run("cp -- $path $legacyPath && chmod 600 $legacyPath").code == 0)
                check(root.list().second.any { it.kind == kind && it.name == legacy.name })
                for (pty in listOf(false, true)) {
                    val token = UUID.randomUUID().toString().also { tokens.add(it) }
                    val session: ExecutionSession = if (pty) PtySession(root, token) else PipeSession(root, token)
                    check(session.run(legacy) {} == 0)
                }
                checks.add("Legacy $kind identities are still listed and executable in Pipe and PTY")
            }
            checkImportCheckbox(fixture, projectName, checks)
        } catch (error: Throwable) { failure = error }
        finally {
            imported.forEach { runCatching { root.delete(it) } }
            projects.forEach { path -> runCatching { root.deletePath(path, true) } }
            createdLegacyDirectories.forEach { path -> runCatching { root.run("rmdir -- ${root.quote(path)}") } }
            tokens.forEach { token -> runCatching {
                root.run("rm -f -- ${root.quote("${root.managedRoot}/tmp/$token.pid")} ${root.quote("${root.managedRoot}/tmp/$token.cancel")}")
            } }
            archive?.parentFile?.let { runCatching { root.deletePath(it.absolutePath, true) } }
            runCatching { root.deletePath(fixture.absolutePath, true) }
        }
        val result = Bundle().apply {
            putString("stream", checks.joinToString("\n", postfix = "\n") +
                (failure?.let { "FAILED: ${it.stackTraceToString()}" } ?: "ALL DEVICE CHECKS PASSED"))
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    /** Device repair uses only the module files embedded in this signed test APK. */
    private fun repairAutomationModule() {
        val staging = File(targetContext.cacheDir, "automation-repair-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = listOf("monitor.sh", "detection.sh", "service.sh", "supervisor.sh", "action.sh", "customize.sh", "module.prop")
            names.forEach { name -> context.assets.open(name).use { input -> File(staging, name).outputStream().use { input.copyTo(it) } } }
            val root = RootExecutor()
            val copies = names.joinToString("\n") { name ->
                "cp ${root.quote(File(staging, name).absolutePath)} \"${'$'}d/.$name.updated\" && chmod ${if (name.endsWith(".sh")) "755" else "644"} \"${'$'}d/.$name.updated\" && mv -f \"${'$'}d/.$name.updated\" \"${'$'}d/$name\""
            }
            val result = root.run("""
                set -eu
                d=/data/adb/modules/tubing_app_trigger
                [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ]
                [ ! -e "${'$'}d/disable" ] && [ ! -e "${'$'}d/remove" ]
                if [ -f "${'$'}d/supervisor.pid" ]; then
                  supervisor=${'$'}(cat "${'$'}d/supervisor.pid")
                  case "${'$'}supervisor" in ''|*[!0-9]*) exit 1 ;; esac
                  if [ -r "/proc/${'$'}supervisor/cmdline" ] && tr '\000' '\n' < "/proc/${'$'}supervisor/cmdline" | grep -Fxq "${'$'}d/supervisor.sh"; then kill "${'$'}supervisor"; fi
                fi
                $copies
                pid=${'$'}(sed -n 's/^pid=//p' "${'$'}d/status.log" | head -n 1)
                case "${'$'}pid" in ''|*[!0-9]*) echo 'Invalid old monitor PID'; exit 1 ;; esac
                if [ -r "/proc/${'$'}pid/cmdline" ] && tr '\000' '\n' < "/proc/${'$'}pid/cmdline" | grep -Fxq "${'$'}d/monitor.sh"; then
                  kill "${'$'}pid"
                fi
                sleep 5
                /system/bin/sh "${'$'}d/service.sh" </dev/null >/dev/null 2>&1
                sleep 6
                cat "${'$'}d/module.prop"
                cat "${'$'}d/status.log"
                cat "${'$'}d/monitor-output.log"
            """.trimIndent())
            check(result.code == 0) { result.output }
            finish(Activity.RESULT_OK, Bundle().apply { putString("automation_repair", result.output) })
        } catch (e: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("automation_repair_error", e.message) })
        } finally { staging.deleteRecursively() }
    }

    private fun checkBrowserPerformance() {
        val preferences = targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previousRecents = preferences.getString("recentBrowserDirectories", null)
        val releaseReads = CountDownLatch(1)
        val childStarted = CountDownLatch(1)
        var activity: Activity? = null
        var requests: BrowserRequestTokens? = null
        val result = runCatching {
            val screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            var ready = false
            repeat(60) { if (!ready) { onUi { ready = field("granted").getBoolean(screen) }; if (!ready) SystemClock.sleep(100) } }
            check(ready)
            val rootReader = RootExecutor()
            var scanStart = SystemClock.uptimeMillis()
            check(rootReader.browse(rootReader.managedRoot).first.code == 0)
            val libraryScanMs = SystemClock.uptimeMillis() - scanStart
            scanStart = SystemClock.uptimeMillis()
            check(rootReader.browse("/storage/emulated/0").first.code == 0)
            val deviceScanMs = SystemClock.uptimeMillis() - scanStart
            val parent = "/performance-fixture/a"
            val child = "$parent/child"
            val other = "/performance-fixture/b"
            val parentEntries = listOf(BrowserEntry(child, "child", "d", 0, 0)) +
                (0 until 120).map { BrowserEntry("$parent/file-$it", "file-$it", "f", 0, 0) }
            val refreshed = parentEntries + BrowserEntry("$parent/new-file", "new-file", "f", 0, 0)
            val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
            val read: (String) -> Pair<CommandResult, List<BrowserEntry>> = { path ->
                requested.add(path)
                if (path == child) childStarted.countDown()
                check(releaseReads.await(5, TimeUnit.SECONDS))
                CommandResult(0, "") to if (path == parent) refreshed else emptyList()
            }
            val render = MainActivity::class.java.getDeclaredMethod("fileManagerPage").apply { isAccessible = true }
            val navigate = MainActivity::class.java.getDeclaredMethod("navigateBrowser", Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
            onUi {
                requests = field("browserRequests").get(screen) as BrowserRequestTokens
                field("browserDirectoryReader").set(screen, read)
                field("tab").setInt(screen, 1)
                field("leftPath").set(screen, parent); field("rightPath").set(screen, other)
                field("leftEntries").set(screen, parentEntries); field("rightEntries").set(screen, parentEntries)
                (field("directoryCache").get(screen) as BrowserDirectoryCache).put(parent, parentEntries)
                render.invoke(screen)
            }
            SystemClock.sleep(150)
            var otherPane: View? = null
            var loadingPaintMs = 0L
            var cachedPaintMs = 0L
            onUi {
                otherPane = (field("browserPanes").get(screen) as ViewGroup).getChildAt(2)
                val start = SystemClock.uptimeMillis()
                navigate.invoke(screen, true, child)
                loadingPaintMs = SystemClock.uptimeMillis() - start
                check((field("browserLoading").get(screen) as BooleanArray)[0])
                check(descendants(screen.window.decorView).filterIsInstance<TextView>().any { it.text == "正在读取目录…" })
                check((field("browserPanes").get(screen) as ViewGroup).getChildAt(2) === otherPane)
            }
            check(childStarted.await(2, TimeUnit.SECONDS))
            onUi {
                val start = SystemClock.uptimeMillis()
                navigate.invoke(screen, true, parent)
                cachedPaintMs = SystemClock.uptimeMillis() - start
                check(field("leftEntries").get(screen) == parentEntries) { "Cache was not displayed before reads completed" }
                check(!(field("browserLoading").get(screen) as BooleanArray)[0])
                val cachedPane = (field("browserPanes").get(screen) as ViewGroup).getChildAt(0)
                check(descendants(cachedPane).filterIsInstance<TextView>().any { it.text == parent })
                check(descendants(cachedPane).filterIsInstance<TextView>().none { it.text == "正在读取目录…" })
                check((field("browserPanes").get(screen) as ViewGroup).getChildAt(2) === otherPane)
            }
            // Complete the obsolete child read and the current parent read together.
            releaseReads.countDown()
            var updated = false
            repeat(80) {
                if (!updated) {
                    onUi { updated = field("leftEntries").get(screen) == refreshed }
                    if (!updated) SystemClock.sleep(25)
                }
            }
            check(updated) { "Background validation did not refresh the cached directory" }
            onUi {
                check(field("leftPath").get(screen) == parent)
                check((field("browserPanes").get(screen) as ViewGroup).getChildAt(2) === otherPane)
                check(!requested.contains(other)) { "Navigation reread the other pane" }
            }
            "Root scan samples: library ${libraryScanMs}ms, device ${deviceScanMs}ms.\nBrowser navigation UI preparation: uncached loading ${loadingPaintMs}ms, cached pane ${cachedPaintMs}ms; both updated before blocked reads completed. Other pane retained; stale child result ignored; cache refreshed in background.\n"
        }
        releaseReads.countDown()
        requests?.invalidateAll()
        activity?.let { screen -> onUi { screen.finish() } }
        preferences.edit().apply {
            if (previousRecents == null) remove("recentBrowserDirectories") else putString("recentBrowserDirectories", previousRecents)
        }.commit()
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.getOrElse { it.stackTraceToString() })
        })
    }

    private fun checkBrowserScrolling() {
        var activity: Activity? = null
        val result = runCatching {
            val screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            var ready = false
            repeat(60) {
                if (!ready) {
                    onUi { ready = field("granted").getBoolean(screen) }
                    if (!ready) SystemClock.sleep(100)
                }
            }
            check(ready) { "Root gate not ready" }
            val render = MainActivity::class.java.getDeclaredMethod("fileManagerPage").apply { isAccessible = true }
            val entries = (0 until 120).map { BrowserEntry("/scroll-fixture/item-$it.sh", "item-%03d.sh".format(it), "f", 10, 0) }
            onUi {
                // Synthetic entries only: do not create files, refresh root paths or change preferences.
                field("tab").setInt(screen, 1)
                field("leftPath").set(screen, "/scroll-fixture/left")
                field("rightPath").set(screen, "/scroll-fixture/right")
                field("leftEntries").set(screen, entries)
                field("rightEntries").set(screen, entries)
                render.invoke(screen)
            }
            SystemClock.sleep(200)
            fun panes() = descendants(screen.window.decorView).filterIsInstance<BrowserScrollView>()
            var left: BrowserScrollView? = null
            var leftOffset = 0
            var rightOffset = 0
            onUi {
                val scrolls = panes()
                check(scrolls.size == 2)
                left = scrolls[0]
                scrolls[0].scrollTo(0, 420)
                scrolls[1].scrollTo(0, 630)
                leftOffset = scrolls[0].scrollY
                rightOffset = scrolls[1].scrollY
                check(leftOffset > 0 && rightOffset > 0)
                render.invoke(screen)
            }
            SystemClock.sleep(200)
            onUi {
                check(panes()[0].scrollY == leftOffset && panes()[1].scrollY == rightOffset) { "Pane offsets lost on rebuild" }
                left = panes()[0]
            }
            val scroll = checkNotNull(left)
            fun gesture(horizontal: Boolean) {
                val downTime = SystemClock.uptimeMillis()
                val startX = scroll.width * if (horizontal) 0.2f else 0.5f
                val startY = scroll.height * 0.7f
                fun event(action: Int, x: Float, y: Float) = onUi {
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
                        scroll.dispatchTouchEvent(it); it.recycle()
                    }
                }
                event(MotionEvent.ACTION_DOWN, startX, startY)
                repeat(10) { step ->
                    SystemClock.sleep(18)
                    val fraction = (step + 1) / 10f
                    event(MotionEvent.ACTION_MOVE, startX + if (horizontal) scroll.width * 0.6f * fraction else 1f,
                        startY - if (horizontal) 1f else scroll.height * 0.35f * fraction)
                }
                event(MotionEvent.ACTION_UP, startX + if (horizontal) scroll.width * 0.6f else 1f,
                    startY - if (horizontal) 1f else scroll.height * 0.35f)
            }
            gesture(false)
            SystemClock.sleep(120)
            onUi {
                check(scroll.scrollY > leftOffset) { "Vertical swipe did not scroll" }
                check((field("leftSelection").get(screen) as BrowserSelection).selectedPaths.isEmpty()) { "Vertical scroll selected a file" }
                // Stop inertia with a tap; this must not become a file click.
                val now = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    MotionEvent.obtain(now, now + 1, action, scroll.width * 0.5f, scroll.height * 0.5f, 0).also {
                        scroll.dispatchTouchEvent(it); it.recycle()
                    }
                }
            }
            SystemClock.sleep(80)
            onUi { check((field("leftSelection").get(screen) as BrowserSelection).selectedPaths.isEmpty()) { "Stopping a fling clicked a file" } }
            gesture(true)
            onUi {
                check((field("leftSelection").get(screen) as BrowserSelection).selectedPaths.size == 1) { "Horizontal swipe did not select one file" }
                check(panes()[1].scrollY == rightOffset) { "Other pane moved" }
            }
            var navigation: ViewGroup? = null
            onUi {
                MainActivity::class.java.getDeclaredMethod("clearBrowserSelection", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(screen, true)
                navigation = descendants(screen.window.decorView).filterIsInstance<Button>().first { it.text == "↑ 上级" }.parent as ViewGroup
            }
            val nav = checkNotNull(navigation)
            val gestureRoot = field("page").get(screen) as ViewGroup
            var gestureOrigin: View = nav
            val density = screen.resources.displayMetrics.density
            var downTime = 0L
            fun dragEvent(action: Int, distance: Float) = onUi {
                if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                val originPosition = IntArray(2).also { gestureOrigin.getLocationOnScreen(it) }
                val rootPosition = IntArray(2).also { gestureRoot.getLocationOnScreen(it) }
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    originPosition[0] - rootPosition[0] + gestureOrigin.width * 0.5f,
                    originPosition[1] - rootPosition[1] + gestureOrigin.height * 0.6f - distance, 0).also {
                    gestureRoot.dispatchTouchEvent(it); it.recycle()
                }
            }
            fun sheet() = field("favoritesSheet").get(screen) as? FavoritesSheet
            dragEvent(MotionEvent.ACTION_DOWN, 0f)
            dragEvent(MotionEvent.ACTION_MOVE, 48 * density)
            SystemClock.sleep(80)
            var firstFraction = 0f
            onUi {
                firstFraction = checkNotNull(sheet()).revealFraction
                check(firstFraction > 0f && firstFraction < 1f) { "Sheet did not reveal before finger release" }
            }
            dragEvent(MotionEvent.ACTION_MOVE, 128 * density)
            onUi { check(checkNotNull(sheet()).revealFraction > firstFraction) { "Reveal did not follow finger movement" } }
            dragEvent(MotionEvent.ACTION_CANCEL, 128 * density)
            SystemClock.sleep(280)
            onUi { check(sheet() == null) { "Cancelled drag left the sheet open" } }
            listOf("文件执行", "文件管理", "设置").forEach { label ->
                onUi { gestureOrigin = descendants(screen.window.decorView).filterIsInstance<Button>().first { it.text == label } }
                dragEvent(MotionEvent.ACTION_DOWN, 0f)
                dragEvent(MotionEvent.ACTION_MOVE, 48 * density)
                SystemClock.sleep(80)
                onUi { check(checkNotNull(sheet()).revealFraction > 0f) { "Bottom tab $label did not reveal sheet" } }
                dragEvent(MotionEvent.ACTION_CANCEL, 48 * density)
                SystemClock.sleep(250)
                onUi { check(sheet() == null && field("tab").getInt(screen) == 1) { "Swipe triggered tab $label" } }
            }
            dragEvent(MotionEvent.ACTION_DOWN, 0f)
            dragEvent(MotionEvent.ACTION_MOVE, 48 * density)
            SystemClock.sleep(80)
            var openDistance = 0f
            onUi { openDistance = checkNotNull(sheet()).contentView.getChildAt(1).height * 0.7f }
            dragEvent(MotionEvent.ACTION_MOVE, openDistance)
            dragEvent(MotionEvent.ACTION_UP, openDistance)
            SystemClock.sleep(350)
            onUi {
                val opened = checkNotNull(sheet())
                check(opened.revealFraction == 1f)
                val panel = opened.contentView.getChildAt(1)
                check(panel.height <= minOf(screen.resources.displayMetrics.heightPixels * 0.6f, 520 * density) + 1)
                check(opened.contentView.height - opened.contentView.paddingBottom - panel.bottom <= 10 * density) { "Sheet is not bottom anchored" }
                screen.onBackPressed()
            }
            SystemClock.sleep(280)
            onUi {
                check(sheet() == null)
                check(field("leftPath").get(screen) == "/scroll-fixture/left") { "Back navigated behind the sheet" }
            }
            dragEvent(MotionEvent.ACTION_DOWN, 0f)
            dragEvent(MotionEvent.ACTION_UP, 0f)
            onUi { check(field("tab").getInt(screen) == 2) { "Normal Settings tap stopped working" } }
            "Browser scrolling and favorites sheet: pane offsets, vertical drag, fling stop, horizontal selection passed; both bottom rows and all three tabs reveal the sheet without switching tabs, drag/cancel/settle/Back pass, and normal Settings tap still works.\n"
        }
        activity?.let { screen -> onUi { screen.finish() } }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.getOrElse { it.stackTraceToString() })
        })
    }

    private fun checkUpdates() {
        val result = runCatching {
            val fixture = """[
                {"tag_name":"v9.0.0","draft":true,"html_url":"https://github.com/givsk983/tubing/releases/tag/v9.0.0"},
                {"tag_name":"v1.4.2","prerelease":true,"body":null,"html_url":"https://github.com/givsk983/tubing/releases/tag/v1.4.2",
                 "assets":[{"name":"tubing-v1.4.2.apk","state":"uploaded","browser_download_url":"https://github.com/givsk983/tubing/releases/download/v1.4.2/tubing-v1.4.2.apk"}]},
                {"tag_name":"v1.4.1","html_url":"https://github.com/givsk983/tubing/releases/tag/v1.4.1"}
            ]"""
            val release = checkNotNull(GitHubUpdateChecker.parse(fixture))
            check(release.tag == "v1.4.2" && release.prerelease && release.notes.isEmpty())
            check(release.apkUrl?.endsWith("/tubing-v1.4.2.apk") == true)
            check(AppUpdates.isNewer(release, "1.4.1"))
            check(GitHubUpdateChecker.parse("[]") == null)
            check(runCatching { GitHubUpdateChecker.parse("unavailable") }.isFailure)
            val withoutApk = checkNotNull(GitHubUpdateChecker.parse("""[{"tag_name":"v1.4.2","html_url":"https://github.com/givsk983/tubing/releases/tag/v1.4.2"}]"""))
            check(withoutApk.apkUrl == null)
            val online = checkNotNull(GitHubUpdateChecker.check()) { "GitHub has no published releases" }
            check(online.apkUrl != null) { "Published APK was not recognized" }
            "Update parser: draft filtering, prereleases, APK matching, empty/malformed data, missing APK fallback passed.\nGitHub HTTPS check: ${online.tag}, APK ${online.apkUrl}\n"
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.getOrElse { it.stackTraceToString() })
        })
    }

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        val completed = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { action() } catch (error: Throwable) { failure = error } finally { completed.countDown() }
        }
        check(completed.await(10, TimeUnit.SECONDS)) {
            "UI action timed out:\n" + Looper.getMainLooper().thread.stackTrace.joinToString("\n")
        }
        failure?.let { throw it }
    }

    private fun cleanupPreviousFixture(id: String) {
        val result = runCatching {
            require(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
            val fixture = File(targetContext.cacheDir, "smoke-$id")
            check(File(fixture, "完整项目.zip").isFile && File(fixture, "permission.sh").isFile) { "Not a recognized test fixture" }
            val root = RootExecutor()
            check(root.initialize().code == 0)
            root.list().second.filter { file -> file.kind in setOf("library", "scripts", "binaries") &&
                listOf("browser-$id", "browser-elf-$id", "permission-$id", "legacy-$id").any { file.name.startsWith(it) }
            }.forEach { check(root.delete(it).code == 0) }
            check(root.deletePath("${root.managedRoot}/项目-$id", true).code == 0)
            check(root.deletePath(fixture.absolutePath, true).code == 0)
            val preferences = targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val saved = org.json.JSONArray(preferences.getString("recentBrowserDirectories", "[]"))
            val retained = org.json.JSONArray()
            for (index in 0 until saved.length()) {
                val path = saved.getString(index)
                if (path != fixture.absolutePath && !path.startsWith(fixture.absolutePath + "/")) retained.put(path)
            }
            preferences.edit().putString("recentBrowserDirectories", retained.toString()).commit()
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "Interrupted test fixture cleaned: $id")
        })
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun checkImportCheckbox(fixture: File, projectName: String, checks: MutableList<String>) {
        val preferences = targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previousRecents = preferences.getString("recentBrowserDirectories", null)
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var ready = false
            repeat(50) {
                if (!ready) {
                    onUi {
                        ready = MainActivity::class.java.getDeclaredField("granted").apply { isAccessible = true }.getBoolean(activity)
                    }
                    if (!ready) SystemClock.sleep(100)
                }
            }
            check(ready) { "Activity root initialization timed out" }
            onUi {
                MainActivity::class.java.getDeclaredMethod("openImportBrowser").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredField("rightPath").apply { isAccessible = true }.set(activity, fixture.absolutePath)
                MainActivity::class.java.getDeclaredMethod("refreshBrowsers", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
            }
            var checkbox: CheckBox? = null
            repeat(50) {
                if (checkbox == null) {
                    onUi {
                        checkbox = descendants(activity.window.decorView).filterIsInstance<CheckBox>().firstOrNull {
                            it.contentDescription == "选择 完整项目.zip"
                        }
                    }
                    if (checkbox == null) SystemClock.sleep(100)
                }
            }
            val box = checkNotNull(checkbox) { "Import checkbox not found" }
            SystemClock.sleep(100)
            var originalHeight = 0
            var originalTop = 0
            onUi {
                val scroll = box.parent.parent.parent as ScrollView
                originalHeight = scroll.height
                originalTop = IntArray(2).also { scroll.getLocationOnScreen(it) }[1]
                box.performClick()
            }
            SystemClock.sleep(100)
            onUi {
                val selection = MainActivity::class.java.getDeclaredField("rightSelection").apply { isAccessible = true }.get(activity) as BrowserSelection
                check(box.isChecked && File(fixture, "完整项目.zip").absolutePath in selection.selectedPaths)
                val scroll = box.parent.parent.parent as ScrollView
                check(scroll.height == originalHeight && IntArray(2).also { scroll.getLocationOnScreen(it) }[1] == originalTop)
                box.performClick()
                check(!box.isChecked && selection.selectedPaths.isEmpty())
            }
            checks.add("Import UI: ZIP checkbox selects and deselects without opening preview or moving the list")
            onUi {
                MainActivity::class.java.getDeclaredMethod("clearBrowserSelection", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                val chooser = MainActivity::class.java.getDeclaredMethod("openFavorites").apply { isAccessible = true }.invoke(activity) as FavoritesSheet
                val rootView = chooser.contentView
                descendants(rootView).filterIsInstance<Button>().first { it.text == "最近" }.performClick()
                check(descendants(rootView).filterIsInstance<TextView>().any { it.text == fixture.absolutePath })
                chooser.dismiss()
            }
            checks.add("Recent directories UI: successfully visited directory is shown in the new Recent tab")
            var selectedProgram = false
            sendStatus(0, Bundle().apply { putString("stream", "Checking grouped project picker\n") })
            onUi {
                val method = MainActivity::class.java.declaredMethods.first { it.name == "showExecutionFilePicker" }.apply { isAccessible = true }
                val callback: () -> Unit = { selectedProgram = true }
                val files = listOf(ManagedFile("project", "$projectName/bin/run (2).SH", "755"))
                val chooser = method.invoke(activity, files, callback) as Dialog
                val rootView = chooser.window!!.decorView
                fun clickTitle(title: String) {
                    sendStatus(0, Bundle().apply { putString("stream", "Click: $title\n") })
                    val label = descendants(rootView).filterIsInstance<TextView>().first { it.text == title }
                    var target: View? = label
                    while (target != null && !target.isClickable) target = target.parent as? View
                    check(checkNotNull(target) { "No clickable card for $title" }.performClick())
                }
                check(descendants(rootView).filterIsInstance<TextView>().none { it.text == "run (2).SH" })
                clickTitle("📁 $projectName")
                check(descendants(rootView).filterIsInstance<TextView>().none { it.text == "run (2).SH" })
                clickTitle("📁 bin")
                clickTitle("run (2).SH")
                val selected = MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) as ManagedFile
                check(selectedProgram && selected.kind == "project" && selected.name == "$projectName/bin/run (2).SH")
            }
            checks.add("Execution picker UI: project stays grouped, nested folders open, selecting a program preserves its full project identity")
        } finally {
            preferences.edit().apply {
                if (previousRecents == null) remove("recentBrowserDirectories") else putString("recentBrowserDirectories", previousRecents)
            }.commit()
        }
    }
}

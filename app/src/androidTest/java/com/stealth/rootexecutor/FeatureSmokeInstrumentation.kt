package com.stealth.rootexecutor

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Device-only checks against the actual Android ZIP, root shell and execution backends. */
class FeatureSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val root = RootExecutor()
        val id = UUID.randomUUID().toString()
        val fixture = File(targetContext.cacheDir, "smoke-$id").apply { mkdirs() }
        val imported = mutableListOf<ManagedFile>()
        val tokens = mutableListOf<String>()
        val createdLegacyDirectories = mutableListOf<String>()
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
        } catch (error: Throwable) { failure = error }
        finally {
            imported.forEach { runCatching { root.delete(it) } }
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
}

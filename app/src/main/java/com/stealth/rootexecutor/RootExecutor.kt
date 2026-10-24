package com.stealth.rootexecutor

import android.util.Base64
import java.io.File
import java.util.UUID

data class ManagedFile(val kind: String, val name: String, val mode: String)
data class CommandResult(val code: Int, val output: String)
data class SourceEntry(val path: String, val directory: Boolean)
data class BrowserEntry(val path: String, val name: String, val type: String, val size: Long, val modified: Long)

/** All paths are constructed from a strict imported-name allowlist and shell-quoted before use. */
class RootExecutor {
    private val base = "/data/adb/root_executor"
    val managedRoot: String get() = base
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun run(command: String, onOutput: (String) -> Unit = {}): CommandResult {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> output.appendLine(line); onOutput(line) } }
        return CommandResult(p.waitFor(), output.toString())
    }

    fun initialize(): CommandResult = run("""
        set -eu
        [ "${'$'}(id -u)" = 0 ] || { echo '未获得 Root 权限'; exit 40; }
        [ ! -L $base ] || { echo '拒绝符号链接根目录'; exit 30; }
        for d in scripts binaries logs tmp; do
          p=$base/${'$'}d
          if [ -L "${'$'}p" ]; then echo "拒绝符号链接目录: ${'$'}p"; exit 31; fi
          [ -d "${'$'}p" ] || mkdir -p "${'$'}p"
          chmod 700 "${'$'}p"
        done
        chmod 700 $base
        echo ROOT_EXECUTOR_READY
    """.trimIndent())

    fun import(local: File, originalName: String, kind: String): CommandResult =
        importSource(local.canonicalPath, originalName, kind)

    /** List the immediate children through the root shell, including protected shared-storage folders. */
    fun listSource(path: String): Pair<CommandResult, List<SourceEntry>> {
        require(path.startsWith('/') && !path.contains('\u0000')) { "请输入有效的绝对路径" }
        val result = run("""
            set -eu
            d=${quote(path)}
            [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || { echo '目录不存在、不可访问或为符号链接'; exit 45; }
            find "${'$'}d" -mindepth 1 -maxdepth 1 -type d -print0 | base64 | tr -d '\n'
            printf '\n'
            find "${'$'}d" -mindepth 1 -maxdepth 1 -type f -print0 | base64 | tr -d '\n'
        """.trimIndent())
        if (result.code != 0) return result to emptyList()
        val rows = result.output.lineSequence().toList()
        fun decode(row: String): List<String> = if (row.isBlank()) emptyList() else
            Base64.decode(row, Base64.DEFAULT).toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        val entries = decode(rows.getOrElse(0) { "" }).map { SourceEntry(it, true) } +
                decode(rows.getOrElse(1) { "" }).map { SourceEntry(it, false) }
        return result to entries
    }

    /** List one directory without losing filenames that contain spaces or punctuation. */
    fun browse(path: String): Pair<CommandResult, List<BrowserEntry>> {
        require(validPath(path)) { "目录路径无效" }
        val result = run("""
            set -eu
            d=${quote(path)}
            [ -d "${'$'}d" ] && [ -r "${'$'}d" ] || { echo '目录不存在或不可读取'; exit 45; }
            find "${'$'}d" -mindepth 1 -maxdepth 1 -type d -print0 | base64 | tr -d '\n'
            printf '\n'
            find "${'$'}d" -mindepth 1 -maxdepth 1 -type f -print0 | base64 | tr -d '\n'
            printf '\n'
            find "${'$'}d" -mindepth 1 -maxdepth 1 -type l -print0 | base64 | tr -d '\n'
        """.trimIndent())
        if (result.code != 0) return result to emptyList()
        val types = listOf("d", "f", "l")
        val entries = result.output.lineSequence().take(3).mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            try {
                Base64.decode(line, Base64.DEFAULT).toString(Charsets.UTF_8).split('\u0000')
                    .filter { it.isNotEmpty() }
                    .mapNotNull { fullPath ->
                        val name = fullPath.substringAfterLast('/')
                        if (name.isBlank() || name == "." || name == "..") null
                        else BrowserEntry(fullPath, name, types[index], 0L, 0L)
                    }
            } catch (_: Exception) { null }
        }.flatten().sortedWith(compareBy<BrowserEntry> { it.type != "d" }.thenBy { it.name.lowercase() }).toList()
        return result to entries
    }

    fun copyPath(source: String, destinationDirectory: String): CommandResult = transferPath("cp -R", source, destinationDirectory)
    fun movePath(source: String, destinationDirectory: String): CommandResult = transferPath("mv", source, destinationDirectory)

    /** Copy a stable private snapshot; its owner must remove the parent directory when finished. */
    fun prepareZip(source: String, cache: File): File {
        require(validPath(source)) { "ZIP 路径无效" }
        val staging = File(cache, "unzip-${UUID.randomUUID()}")
        check(staging.mkdir()) { "无法创建临时目录" }
        try {
            val archive = File(staging, "archive.zip")
            val copied = run("""
                set -eu
                s=${quote(source)}; f=${quote(archive.absolutePath)}
                [ -f "${'$'}s" ] && [ ! -L "${'$'}s" ] || { echo 'ZIP 文件不存在或为符号链接'; exit 46; }
                [ "${'$'}(stat -c %s "${'$'}s")" -le ${ZipExtractor.MAX_ARCHIVE_BYTES} ] || { echo 'ZIP 文件超过 512 MB'; exit 47; }
                cp -- "${'$'}s" "${'$'}f"
                chown ${android.os.Process.myUid()} "${'$'}f"
                chmod 600 "${'$'}f"
            """.trimIndent())
            check(copied.code == 0) { copied.output.ifBlank { "无法读取 ZIP 文件" } }
            require(archive.length() <= ZipExtractor.MAX_ARCHIVE_BYTES) { "ZIP 文件超过 512 MiB" }
            return archive
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Extract privately first, then publish into a newly created directory; never merge existing files. */
    fun extractZip(source: String, parent: String, folder: String, cache: File): String {
        require(validPath(parent) && validLeafName(folder)) { "解压路径或文件夹名称无效" }
        val archive = prepareZip(source, cache)
        val staging = archive.parentFile!!
        try {
            val output = File(staging, "output")
            val count = ZipExtractor.extract(archive, output)
            val published = run("""
                set -eu
                cd -- ${quote(parent)}
                target=${quote("./$folder")}
                [ ! -e "${'$'}target" ] && [ ! -L "${'$'}target" ] || { echo '目标已存在，请更换文件夹名称'; exit 49; }
                mkdir -- "${'$'}target"
                trap 'rm -rf -- "${'$'}target"' EXIT
                cp -R -- ${quote(output.absolutePath + "/.")} "${'$'}target/"
                trap - EXIT
            """.trimIndent())
            check(published.code == 0) { published.output.ifBlank { "无法保存解压文件" } }
            return "已解压 $count 个文件\n${parent.trimEnd('/')}/$folder"
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Publish just the selected file/folder names directly into the requested directory. */
    fun extractZipSelection(archive: File, parent: String, selection: Set<String>): String {
        require(validPath(parent)) { "解压路径无效" }
        val output = File(archive.parentFile, "selection-${UUID.randomUUID()}")
        try {
            val count = ZipExtractor.extract(archive, output, selection = selection)
            val names = output.listFiles()?.map { it.name }?.sorted().orEmpty()
            require(names.isNotEmpty() && names.all { validLeafName(it) }) { "没有可解压的条目" }
            val stage = "./.tubing-extract-${UUID.randomUUID()}"
            val rollback = buildString {
                names.forEachIndexed { index, name ->
                    append("if [ \"${'$'}m$index\" = 1 ]; then rm -rf -- ${quote("./$name")}; fi\n")
                }
                append("rm -rf -- ${quote(stage)}")
            }
            val command = buildString {
                append("set -eu\ncd -- ${quote(parent)}\n")
                names.forEachIndexed { index, name ->
                    val target = quote("./$name")
                    append("[ ! -e $target ] && [ ! -L $target ] || { echo ${quote("目标已存在：$name，请改用其他目录")}; exit 49; }\n")
                    append("m$index=0\n")
                }
                append("mkdir -- ${quote(stage)}\ntrap ${quote(rollback)} EXIT\n")
                append("cp -R -- ${quote(output.absolutePath + "/.")} ${quote("$stage/")}\n")
                names.forEachIndexed { index, name ->
                    val staged = quote("$stage/$name")
                    append("mv -nT -- $staged ${quote("./$name")}\n")
                    append("[ ! -e $staged ] && [ ! -L $staged ] || { echo '目标名称已被占用，已取消解压'; exit 49; }\n")
                    append("m$index=1\n")
                }
                append("rmdir -- ${quote(stage)}\ntrap - EXIT\n")
            }
            val result = run(command)
            check(result.code == 0) { result.output.ifBlank { "无法保存解压文件" } }
            return "已解压 $count 个文件到\n$parent\n\n${names.joinToString("\n") }"
        } finally {
            output.deleteRecursively()
        }
    }

    private fun transferPath(command: String, source: String, destinationDirectory: String): CommandResult {
        require(validPath(source) && validPath(destinationDirectory)) { "文件路径无效" }
        require(source != "/" && source != destinationDirectory && !destinationDirectory.startsWith(source.trimEnd('/') + "/")) { "不能将目录复制到自身内部" }
        return run("""
            set -eu
            s=${quote(source)}; d=${quote(destinationDirectory)}; n=${'$'}{s##*/}; target=${'$'}d/${'$'}n
            [ -e "${'$'}s" ] || [ -L "${'$'}s" ] || { echo '源文件不存在'; exit 46; }
            [ -d "${'$'}d" ] && [ -w "${'$'}d" ] || { echo '目标目录不可写'; exit 48; }
            [ ! -e "${'$'}target" ] && [ ! -L "${'$'}target" ] || { echo '目标中已存在同名文件'; exit 49; }
            $command -- "${'$'}s" "${'$'}d/"
        """.trimIndent())
    }

    fun renamePath(path: String, newName: String): CommandResult {
        require(validPath(path) && path != "/") { "文件路径无效" }
        require(validLeafName(newName)) { "名称无效" }
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        return run("""
            set -eu
            s=${quote(path)}; d=${quote("${parent.trimEnd('/')}/$newName")}
            [ -e "${'$'}s" ] || [ -L "${'$'}s" ] || { echo '文件不存在'; exit 46; }
            [ ! -e "${'$'}d" ] && [ ! -L "${'$'}d" ] || { echo '目标名称已存在'; exit 49; }
            mv -- "${'$'}s" "${'$'}d"
        """.trimIndent())
    }

    fun deletePath(path: String, recursive: Boolean): CommandResult {
        require(validPath(path) && path != "/") { "不能删除根目录" }
        val remove = if (recursive) "rm -rf --" else "rm -f --"
        return run("""
            set -eu
            p=${quote(path)}
            [ -e "${'$'}p" ] || [ -L "${'$'}p" ] || { echo '文件不存在'; exit 46; }
            $remove "${'$'}p"
        """.trimIndent())
    }

    fun makeDirectory(parent: String, name: String): CommandResult {
        require(validPath(parent) && validLeafName(name)) { "目录名称或路径无效" }
        return run("""
            set -eu
            d=${quote(parent)}; target=${quote("${parent.trimEnd('/')}/$name")}
            [ -d "${'$'}d" ] && [ -w "${'$'}d" ] || { echo '当前目录不可写'; exit 48; }
            mkdir -- "${'$'}target"
        """.trimIndent())
    }

    private fun validPath(path: String) = path.startsWith('/') && !path.contains('\u0000') && path.split('/').none { it == ".." }
    private fun validLeafName(name: String) = name.isNotBlank() && name !in setOf(".", "..") && !name.contains('/') && !name.contains('\u0000')

    /** Read only the header through root; normal import rechecks the source and ELF signature. */
    fun importBrowserSource(sourcePath: String, originalName: String): CommandResult {
        require(validPath(sourcePath)) { "源文件路径无效" }
        val header = run("""
            set -eu
            s=${quote(sourcePath)}
            [ -f "${'$'}s" ] && [ ! -L "${'$'}s" ] || { echo '请选择普通文件，不能导入目录或符号链接'; exit 46; }
            head -c 4 "${'$'}s" | base64
        """.trimIndent())
        if (header.code != 0) return header
        val kind = ZipExtractor.importKind(originalName, Base64.decode(header.output.trim(), Base64.DEFAULT))
        return importSource(sourcePath, originalName, kind)
    }

    fun importSource(sourcePath: String, originalName: String, kind: String): CommandResult {
        require(kind == "scripts" || kind == "binaries")
        require(sourcePath.startsWith('/') && !sourcePath.contains('\u0000')) { "源文件路径无效" }
        val safe = originalName.take(100).trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimEnd('.')
        require(safe.isNotBlank() && safe != "." && safe != "..") { "文件名无效" }
        if (kind == "scripts") require(safe.endsWith(".sh", true)) { "请选择 .sh 脚本" }
        val unique = "${safe.substringBeforeLast('.', safe)}-${UUID.randomUUID().toString().take(8)}" +
                (if (safe.contains('.')) ".${safe.substringAfterLast('.')}" else "")
        val dest = "$base/$kind/$unique"
        val src = sourcePath
        return run("""
            set -eu
            d=${quote("$base/$kind")}; f=${quote(dest)}; s=${quote(src)}
            [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || { echo '目标目录异常'; exit 32; }
            [ -f "${'$'}s" ] && [ ! -L "${'$'}s" ] || { echo '源文件不存在、不可访问或为符号链接'; exit 46; }
            ${if (kind == "binaries") "[ \"\$(head -c 4 \"\$s\" | od -An -tx1 | tr -d ' \\n')\" = 7f454c46 ] || { echo '文件不是 ELF 二进制'; exit 47; }" else ":"}
            [ ! -e "${'$'}f" ] && [ ! -L "${'$'}f" ] || { echo '目标文件已存在'; exit 33; }
            cp -- "${'$'}s" "${'$'}f"
            chmod 777 "${'$'}f"
            [ "${'$'}(stat -c %a "${'$'}f")" = 777 ] || { echo '权限验证失败'; exit 34; }
            echo ${quote("IMPORTED|$kind|$unique")}
        """.trimIndent())
    }

    fun list(): Pair<CommandResult, List<ManagedFile>> {
        val result = run("""
            set -eu
            for k in scripts binaries; do
              d=$base/${'$'}k
              [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || continue
              find "${'$'}d" -mindepth 1 -maxdepth 1 -type f | while IFS= read -r f; do
                printf '%s|%s|%s\n' "${'$'}k" "${'$'}{f##*/}" "${'$'}(stat -c %a "${'$'}f")"
              done
            done
        """.trimIndent())
        val files = result.output.lineSequence().mapNotNull { line ->
            val a = line.split('|'); if (a.size == 3 && a[0] in setOf("scripts", "binaries") && validName(a[1])) ManagedFile(a[0], a[1], a[2]) else null
        }.toList()
        return result to files
    }

    fun delete(file: ManagedFile): CommandResult {
        require(validName(file.name) && file.kind in setOf("scripts", "binaries"))
        val path = "$base/${file.kind}/${file.name}"
        return run("[ ! -L ${quote(path)} ] && [ -f ${quote(path)} ] && rm -- ${quote(path)}")
    }

    fun logs(): CommandResult = run("find ${quote("$base/logs")} -maxdepth 1 -type f -name '*.log' -print -exec tail -n 200 {} \\;")
    fun cleanTmp(): CommandResult = run("[ -d ${quote("$base/tmp")} ] && [ ! -L ${quote("$base/tmp")} ] && find ${quote("$base/tmp")} -mindepth 1 -maxdepth 1 -type f -delete")
    private fun validName(name: String) = name.matches(Regex("[\\p{L}\\p{N}._ -]{1,160}")) && name != "." && name != ".."

    fun sessionCommand(file: ManagedFile, slave: String?, token: String): String {
        require(validName(file.name) && file.kind in setOf("scripts", "binaries"))
        require((slave == null || slave.matches(Regex("/dev/pts/[0-9]+"))) && token.matches(Regex("[a-f0-9-]+")))
        val path = "$base/${file.kind}/${file.name}"
        val launch = if (file.kind == "scripts") "sh ${quote(path)}" else quote(path)
        val inner = """
            ${if (slave != null) "exec 0<${quote(slave)} 1>${quote(slave)} 2>&1" else ": 管道模式保留标准输入输出"}
            ${if (slave != null) "export TERM=xterm-256color COLORTERM=truecolor" else "export TERM=dumb; unset COLORTERM"}
            [ ! -e ${quote("$base/tmp/$token.cancel")} ] || exit 130
            echo ${'$'}${'$'} > ${quote("$base/tmp/$token.pid")}
            [ ! -e ${quote("$base/tmp/$token.cancel")} ] || exit 130
            cd ${quote("$base/${file.kind}")} || exit 41
            echo "[SELinux] ${'$'}(getenforce 2>/dev/null || echo 未知)"
            $launch
            rc=${'$'}?
            printf '\r\n[退出码] %s\r\n' "${'$'}rc"
            exit "${'$'}rc"
        """.trimIndent()
        return """
            set -eu
            [ "${'$'}(id -u)" = 0 ] || { echo 'Root 授权已失效'; exit 40; }
            for d in $base $base/${file.kind} $base/logs $base/tmp; do
              [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || { echo '执行目录异常'; exit 32; }
            done
            f=${quote(path)}
            [ -f "${'$'}f" ] && [ ! -L "${'$'}f" ] || { echo '文件不存在或为符号链接'; exit 35; }
            chmod 777 "${'$'}f"
            [ "${'$'}(stat -c %a "${'$'}f")" = 777 ] || { echo '权限验证失败'; exit 38; }
            opts=${'$'}(awk -v p="${'$'}f" '(${ '$' }2=="/" || index(p,${ '$' }2 "/")==1) && length(${ '$' }2)>m {m=length(${ '$' }2);o=${ '$' }4} END{print o}' /proc/mounts)
            case ",${'$'}opts," in *,noexec,*) echo '文件系统禁止执行（noexec）'; exit 39;; esac
            command -v setsid >/dev/null || { echo '系统缺少 setsid，无法创建执行会话'; exit 42; }
            exec setsid -w sh -c ${quote(inner)}
        """.trimIndent()
    }

    fun signalSession(token: String, signal: String) {
        require(token.matches(Regex("[a-f0-9-]+")) && signal in setOf("INT", "TERM"))
        val pid = quote("$base/tmp/$token.pid")
        run("if [ -f $pid ] && [ ! -L $pid ]; then p=\$(cat $pid); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; kill -$signal -- -\"\$p\"; fi")
    }

    fun stopSession(token: String) {
        require(token.matches(Regex("[a-f0-9-]+")))
        val pid = quote("$base/tmp/$token.pid")
        val cancel = quote("$base/tmp/$token.cancel")
        run("[ -d $base/tmp ] && [ ! -L $base ] && [ ! -L $base/tmp ] || exit 32; (umask 077; set -C; : > $cancel) 2>/dev/null || true; if [ -f $pid ] && [ ! -L $pid ]; then p=\$(cat $pid); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; kill -TERM -- -\"\$p\" 2>/dev/null || true; sleep 1; kill -KILL -- -\"\$p\" 2>/dev/null || true; rm -f $pid; fi")
    }

    fun saveSession(local: File, token: String): CommandResult {
        require(token.matches(Regex("[a-f0-9-]+")))
        val dest = quote("$base/logs/$token.log")
        return run("[ -d $base/logs ] && [ ! -L $base ] && [ ! -L $base/logs ] && [ ! -e $dest ] && [ ! -L $dest ] && cp ${quote(local.absolutePath)} $dest && chmod 600 $dest")
    }
}

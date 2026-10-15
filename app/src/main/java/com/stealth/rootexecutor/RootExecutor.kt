package com.stealth.rootexecutor

import java.io.File
import java.util.UUID

data class ManagedFile(val kind: String, val name: String, val mode: String)
data class CommandResult(val code: Int, val output: String)

/** All paths are constructed from a strict imported-name allowlist and shell-quoted before use. */
class RootExecutor {
    private val base = "/data/adb/root_executor"
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

    fun import(local: File, originalName: String, kind: String): CommandResult {
        require(kind == "scripts" || kind == "binaries")
        val safe = originalName.take(100).trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimEnd('.')
        require(safe.isNotBlank() && safe != "." && safe != "..") { "文件名无效" }
        val unique = "${safe.substringBeforeLast('.', safe)}-${UUID.randomUUID().toString().take(8)}" +
                (if (safe.contains('.')) ".${safe.substringAfterLast('.')}" else "")
        val dest = "$base/$kind/$unique"
        val src = local.canonicalPath
        return run("""
            set -eu
            d=${quote("$base/$kind")}; f=${quote(dest)}; s=${quote(src)}
            [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || { echo '目标目录异常'; exit 32; }
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

    fun sessionCommand(file: ManagedFile, slave: String, token: String): String {
        require(validName(file.name) && file.kind in setOf("scripts", "binaries"))
        require(slave.matches(Regex("/dev/pts/[0-9]+")) && token.matches(Regex("[a-f0-9-]+")))
        val path = "$base/${file.kind}/${file.name}"
        val launch = if (file.kind == "scripts") "sh ${quote(path)}" else quote(path)
        val inner = """
            exec 0<${quote(slave)} 1>${quote(slave)} 2>&1
            export TERM=xterm-256color COLORTERM=truecolor
            echo ${'$'}${'$'} > ${quote("$base/tmp/$token.pid")}
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
            command -v setsid >/dev/null || { echo '系统缺少 setsid，无法创建交互终端'; exit 42; }
            exec setsid -w sh -c ${quote(inner)}
        """.trimIndent()
    }

    fun stopSession(token: String) {
        require(token.matches(Regex("[a-f0-9-]+")))
        val pid = quote("$base/tmp/$token.pid")
        run("if [ -f $pid ] && [ ! -L $pid ]; then p=\$(cat $pid); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; kill -TERM -- -\"\$p\" 2>/dev/null || true; sleep 1; kill -KILL -- -\"\$p\" 2>/dev/null || true; rm -f $pid; fi")
    }

    fun saveSession(local: File, token: String): CommandResult {
        require(token.matches(Regex("[a-f0-9-]+")))
        val dest = quote("$base/logs/$token.log")
        return run("[ -d $base/logs ] && [ ! -L $base ] && [ ! -L $base/logs ] && [ ! -e $dest ] && [ ! -L $dest ] && cp ${quote(local.absolutePath)} $dest && chmod 600 $dest")
    }
}

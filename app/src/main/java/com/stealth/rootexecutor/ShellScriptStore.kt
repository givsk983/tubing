package com.stealth.rootexecutor

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64

/** The editor works on text only; byte encoding is retained when writing a changed document. */
internal data class ShellScriptDocument(
    val path: String, val text: String, val fingerprint: String,
    val bom: Boolean = false, val crlf: Boolean = false
) {
    fun encode(value: String): ByteArray {
        require(value.none { it.isISOControl() && it !in "\t\r\n" }) { "脚本不能包含二进制控制字符" }
        val content = (if (bom) "\uFEFF" else "") +
            if (crlf) value.replace("\r\n", "\n").replace("\n", "\r\n") else value
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: Exception) { throw IllegalArgumentException("内容包含不完整的 Unicode 字符，请修正后保存") }
        require(bytes.size <= MAX_BYTES) { "脚本超过 256 KiB，请缩小后再保存" }
        return bytes
    }

    companion object {
        const val MAX_BYTES = 256 * 1024

        fun decode(path: String, bytes: ByteArray, fingerprint: String): ShellScriptDocument {
            require(bytes.size <= MAX_BYTES) { "脚本超过 256 KiB，暂不支持编辑" }
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw IllegalArgumentException("仅支持 UTF-8 文本脚本，无法编辑二进制或其他编码文件") }
            require(decoded.none { it.isISOControl() && it !in "\t\r\n" }) { "文件包含二进制控制字符，无法编辑" }
            val text = decoded.removePrefix("\uFEFF")
            // Normalize uniform CRLF for the Android input field, then restore it on save.
            val crlf = "\r\n" in text && '\n' !in text.replace("\r\n", "")
            return ShellScriptDocument(path, if (crlf) text.replace("\r\n", "\n") else text,
                fingerprint, decoded.startsWith("\uFEFF"), crlf)
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal data class ShellScriptSave(val document: ShellScriptDocument, val backupPath: String)

/** No shell source is evaluated. Root reads/writes bytes using bounded, quoted operations. */
internal class ShellScriptStore(
    private val root: RootExecutor = RootExecutor(),
    private val execute: (String, Long, Int?) -> CommandResult = { command, seconds, limit -> root.runBounded(command, seconds, limit) }
) {
    private fun checkedPath(path: String) {
        require(path.startsWith('/') && path.endsWith(".sh", true) &&
            '\u0000' !in path && path.split('/').none { it == ".." }) { "请选择普通 .sh 脚本" }
    }

    private val fingerprintFunction = """
        fingerprint() {
          stat -c '%d:%i:%s:%Y:%Z:%a:%u:%g' "${'$'}f"
          sha256sum "${'$'}f" | cut -d ' ' -f 1
        }
    """.trimIndent()

    fun read(path: String): ShellScriptDocument {
        checkedPath(path)
        val result = execute("""
            set -eu -o pipefail
            f=${root.quote(path)}
            [ -f "${'$'}f" ] && [ ! -L "${'$'}f" ] || { echo '脚本不存在或为符号链接'; exit 46; }
            f=${'$'}(readlink -f "${'$'}f")
            [ -f "${'$'}f" ] && [ ! -L "${'$'}f" ] || exit 46
            [ "${'$'}(stat -c %s "${'$'}f")" -le ${ShellScriptDocument.MAX_BYTES} ] || { echo '脚本超过 256 KiB，暂不支持编辑'; exit 47; }
            $fingerprintFunction
            before=${'$'}(fingerprint)
            content=${'$'}(head -c ${ShellScriptDocument.MAX_BYTES + 1} "${'$'}f" | base64 | tr -d '\n')
            [ "${'$'}before" = "${'$'}(fingerprint)" ] || { echo '文件正在被修改，请重新打开'; exit 49; }
            printf '%s' "${'$'}f" | base64 | tr -d '\n'; printf '\n'
            printf '%s' "${'$'}before" | base64 | tr -d '\n'; printf '\n'
            printf '%s\n' "${'$'}content"
        """.trimIndent(), 10, ShellScriptDocument.MAX_BYTES * 2)
        check(result.code == 0) { result.output.ifBlank { "无法读取脚本（${result.code}）" } }
        val rows = result.output.lineSequence().take(3).toList()
        check(rows.size == 3) { "脚本读取不完整，请重试" }
        val decoder = Base64.getDecoder()
        val actualPath = decoder.decode(rows[0]).toString(Charsets.UTF_8)
        checkedPath(actualPath)
        val bytes = decoder.decode(rows[2])
        val fingerprint = decoder.decode(rows[1]).toString(Charsets.UTF_8)
        check(fingerprint.substringAfterLast('\n') == ShellScriptDocument.sha256(bytes)) { "脚本读取校验失败，请重试" }
        return ShellScriptDocument.decode(actualPath, bytes, fingerprint)
    }

    fun save(document: ShellScriptDocument, text: String, cache: File): ShellScriptSave {
        checkedPath(document.path)
        val bytes = document.encode(text)
        val attributes = if (cache.toPath().fileSystem.supportedFileAttributeViews().contains("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        val staged = Files.createTempFile(cache.toPath(), "sh-editor-", ".tmp", *attributes).toFile()
        try {
            staged.writeBytes(bytes)
            val parents = document.path.split('/').filter { it.isNotEmpty() }.dropLast(1)
                .runningFold("") { parent, part -> "$parent/$part" }.drop(1)
            val result = execute("""
                set -eu -o pipefail
                f=${root.quote(document.path)}
                ${parents.joinToString("\n") { "[ -d ${root.quote(it)} ] && [ ! -L ${root.quote(it)} ] || { echo '目录已变化，请重新打开脚本'; exit 49; }" }}
                [ -f "${'$'}f" ] && [ ! -L "${'$'}f" ] || { echo '脚本不存在或为符号链接'; exit 46; }
                $fingerprintFunction
                expected=${root.quote(document.fingerprint)}
                unchanged() { [ "${'$'}(fingerprint)" = "${'$'}expected" ] || { echo '文件已被其他工具修改，草稿已保留。请重新打开并合并修改。'; exit 49; }; }
                unchanged
                d=${'$'}{f%/*}; [ -n "${'$'}d" ] || d=/
                tmp=${'$'}(mktemp "${'$'}d/.tubing-edit.XXXXXX")
                backup=''; keep_backup=0
                cleanup() {
                  rm -f -- "${'$'}tmp"
                  if [ -n "${'$'}backup" ]; then
                    if [ "${'$'}keep_backup" = 0 ]; then rm -f -- "${'$'}backup"; else printf '备份：%s\n' "${'$'}backup"; fi
                  fi
                }
                trap cleanup EXIT
                cp -p -- "${'$'}f" "${'$'}tmp"
                cat -- ${root.quote(staged.absolutePath)} > "${'$'}tmp"
                [ "${'$'}(sha256sum "${'$'}tmp" | cut -d ' ' -f 1)" = ${root.quote(ShellScriptDocument.sha256(bytes))} ] || { echo '写入校验失败'; exit 50; }
                [ "${'$'}(stat -c '%a:%u:%g' "${'$'}f")" = "${'$'}(stat -c '%a:%u:%g' "${'$'}tmp")" ] || { echo '无法保留脚本权限与所有者'; exit 50; }
                preserve_context() {
                  context=${'$'}(ls -Zd -- "${'$'}f"); context=${'$'}{context%% *}
                  case "${'$'}context" in *:*:*)
                    dest_context=${'$'}(ls -Zd -- "${'$'}1"); dest_context=${'$'}{dest_context%% *}
                    [ "${'$'}context" = "${'$'}dest_context" ] || chcon "${'$'}context" "${'$'}1" ;;
                  esac
                }
                preserve_context "${'$'}tmp"
                unchanged
                backup=${'$'}(mktemp "${'$'}d/.tubing-backup-${'$'}(date +%Y%m%d-%H%M%S).XXXXXX")
                cp -p -- "${'$'}f" "${'$'}backup"
                [ "${'$'}(sha256sum "${'$'}backup" | cut -d ' ' -f 1)" = ${root.quote(document.fingerprint.substringAfterLast('\n'))} ] || { echo '备份校验失败，未覆盖原文件'; exit 50; }
                preserve_context "${'$'}backup"
                keep_backup=1
                unchanged
                mv -fT -- "${'$'}tmp" "${'$'}f"
                printf 'SAVED|'; fingerprint | base64 | tr -d '\n'; printf '\n'
                printf 'BACKUP|'; printf '%s' "${'$'}backup" | base64 | tr -d '\n'; printf '\n'
            """.trimIndent(), 15, null)
            check(result.code == 0) { result.output.ifBlank { "保存未确认（${result.code}），草稿已保留。请检查原文件后重试。" } }
            fun field(prefix: String) = Base64.getDecoder().decode(result.output.lineSequence()
                .first { it.startsWith(prefix) }.removePrefix(prefix)).toString(Charsets.UTF_8)
            val fingerprint = field("SAVED|").trimEnd('\n')
            check(fingerprint.substringAfterLast('\n') == ShellScriptDocument.sha256(bytes)) { "保存后文件发生变化，请重新打开检查；草稿已保留" }
            return ShellScriptSave(document.copy(text = text, fingerprint = fingerprint), field("BACKUP|"))
        } finally { staged.delete() }
    }
}

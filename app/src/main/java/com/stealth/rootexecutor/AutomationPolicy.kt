package com.stealth.rootexecutor

internal data class AutomationPolicy(val cooldownSeconds: Int = 60, val timeoutSeconds: Int = 120, val once: Boolean = false) {
    fun blockedReason(now: Long, lastAttempt: Long, consumed: Boolean): String? {
        if (once && consumed) return "单次启用已使用，请重新启用"
        val elapsed = (now - lastAttempt).coerceAtLeast(0)
        if (lastAttempt > 0 && elapsed < cooldownSeconds * 1000L)
            return "冷却中，剩余 ${(cooldownSeconds * 1000L - elapsed + 999) / 1000} 秒"
        return null
    }
}

internal data class ForegroundIdentity(val userId: Int, val packageName: String)

internal object ForegroundParser {
    fun identity(activities: String): ForegroundIdentity? {
        val lines = activities.lineSequence().toList()
        val line = lines.firstOrNull { "topResumedActivity" in it }
            ?: lines.firstOrNull { "mResumedActivity" in it } ?: return null
        val match = Regex("\\bu([0-9]+) ([A-Za-z][A-Za-z0-9_.]*)/").find(line) ?: return null
        return ForegroundIdentity(match.groupValues[1].toIntOrNull() ?: return null, match.groupValues[2])
    }
    fun packageName(activities: String): String? = identity(activities)?.packageName
}

/** Literal prompt matching, bounded memory, and explicit consumption prevent stale matches. */
internal class PromptBuffer {
    private val raw = StringBuilder()
    @Synchronized fun append(text: String) {
        raw.append(text)
        if (raw.length > 65_536) raw.delete(0, raw.length - 32_768)
    }
    @Synchronized fun consume(prompt: String): Boolean {
        require(prompt.isNotBlank())
        if (!LogDisplay.clean(raw.toString()).contains(prompt)) return false
        raw.clear()
        return true
    }
    @Synchronized fun clear() { raw.clear() }
}

/** Masks registered sensitive inputs even when terminal echoes split across chunks. */
internal class SecretRedactor(values: List<String>) {
    private val secrets = values.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    private val pending = StringBuilder()
    @Synchronized fun accept(text: String): String {
        if (secrets.isEmpty()) return text
        pending.append(text)
        val output = StringBuilder()
        while (pending.isNotEmpty()) {
            val full = secrets.mapNotNull { secret -> pending.indexOf(secret).takeIf { it >= 0 }?.let { it to secret } }
                .minByOrNull { it.first }
            if (full != null) {
                output.append(pending.substring(0, full.first)).append("[已隐藏]")
                pending.delete(0, full.first + full.second.length)
                continue
            }
            var keep = 0
            secrets.forEach { secret ->
                for (length in 1 until minOf(secret.length, pending.length + 1)) {
                    if (length > keep && pending.substring(pending.length - length) == secret.substring(0, length)) keep = length
                }
            }
            output.append(pending.substring(0, pending.length - keep))
            pending.delete(0, pending.length - keep)
            break
        }
        return output.toString()
    }
    @Synchronized fun finish(): String = if (pending.isEmpty()) "" else "[已隐藏]".also { pending.clear() }
}

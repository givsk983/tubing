package com.stealth.rootexecutor

internal enum class InputSendMode(val key: String, val label: String) {
    DELAY("delay", "延时发送"), PROMPT("prompt", "等待提示词");

    fun valid(steps: List<String>, prompts: List<String>): Boolean =
        steps.size <= 50 && steps.all { it.length <= 4096 } &&
            (this != PROMPT || (steps.size == prompts.size && prompts.all { it.isNotBlank() && it.length <= 512 }))

    companion object {
        // Missing metadata belongs to an old preset: preserve its original timing.
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DELAY
        const val INITIAL_DELAY_MS = 1_500L
        const val BETWEEN_INPUTS_MS = 900L
    }
}

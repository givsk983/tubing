package com.stealth.rootexecutor

internal enum class BrowserTransitionStyle(
    val key: String,
    val label: String,
    val description: String,
    val durationMs: Long,
    val distanceDp: Int,
    val startAlpha: Float,
    val deceleration: Float
) {
    FRESH("fresh", "清爽", "轻微滑动，干净利落", 120, 8, 0.4f, 1.2f),
    ELEGANT("elegant", "优雅", "柔和淡入，舒缓滑动", 180, 18, 0f, 1.5f),
    FAST("fast", "快速", "短促淡入，快速完成切换", 80, 0, 0.7f, 1f);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: FRESH
    }
}

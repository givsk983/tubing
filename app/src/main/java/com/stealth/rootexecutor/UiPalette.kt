package com.stealth.rootexecutor

import android.graphics.Color

/** Shared semantic colors for both appearance modes. */
internal data class UiPalette(
    val background: Int, val surface: Int, val text: Int,
    val muted: Int, val accent: Int, val outline: Int, val selected: Int
) {
    companion object {
        private fun hex(value: String) = Color.parseColor(value)
        private val dark = UiPalette(
            hex("#101719"), hex("#192326"), hex("#E7F0EF"),
            hex("#A1B5B5"), hex("#69D8CA"), hex("#304144"), hex("#203E3C")
        )
        private val light = UiPalette(
            hex("#F3F6F5"), hex("#FFFFFF"), hex("#182D2C"),
            hex("#5B716F"), hex("#006D64"), hex("#DAE5E2"), hex("#DDF2EC")
        )
        fun forTheme(isDark: Boolean) = if (isDark) dark else light
    }
}

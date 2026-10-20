package com.stealth.rootexecutor

/** Plain-text presentation only; saved logs remain unchanged. */
internal object LogDisplay {
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val csi = Regex("(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]")
    // Some shell output drops ESC or uses commas in SGR color sequences.
    private val orphanColor = Regex("\\[(?:\\d{1,3}(?:[;,]\\d{1,3})*)?m")
    private val controls = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    fun clean(raw: String): String = raw
        .replace(osc, "")
        .replace(csi, "")
        .replace(orphanColor, "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(controls, "")
}

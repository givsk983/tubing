package com.stealth.rootexecutor

import org.junit.Assert.assertEquals
import org.junit.Test

class LogDisplayTest {
    @Test fun removesTerminalFormattingWithoutLosingText() {
        assertEquals("已连接\n下一行", LogDisplay.clean("\u001B[36m已连接\u001B[0m\r\n下一行"))
        assertEquals("标题正文", LogDisplay.clean("\u001B]0;窗口标题\u0007标题\u001B[2K正文"))
        assertEquals("正文", LogDisplay.clean("\u001B]0;窗口标题\u001B\\正文"))
    }

    @Test fun handlesColorFragmentsShownInLegacyLogs() {
        assertEquals("[tubing] 已连接\n公告", LogDisplay.clean("[36m[tubing] 已连接[0m\n[0,36m公告"))
    }

    @Test fun preservesOrdinaryBracketsUnicodeAndIndentation() {
        val text = "[SELinux] Enforcing\n[12345] 文件路径 /a/b\n\t输入 12345，免费使用"
        assertEquals(text, LogDisplay.clean(text))
        assertEquals("", LogDisplay.clean("\u001B[0m"))
    }
}

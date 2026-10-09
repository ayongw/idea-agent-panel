package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 耗时格式化契约（参照 Kiro：`40s` / `3m 50s`）。
 *
 * 这里的期望值同时钉住两条 UX 决策：
 * - <1s 不展示（流式首帧只差几十毫秒，显示「0s」像卡住）
 * - 整分钟不带多余的 `0s`（`3m` 而不是 `3m 0s`）
 */
class ElapsedTimeFormatUnitTest {

    private fun fmt(seconds: Long): String = formatElapsed(seconds)

    @Test
    fun 不足一秒不展示() {
        assertEquals("", fmt(0))
        assertEquals("", fmt(-1))
    }

    @Test
    fun 一分钟内用秒() {
        assertEquals("1s", fmt(1))
        assertEquals("40s", fmt(40))
        assertEquals("59s", fmt(59))
    }

    @Test
    fun 整分钟不带零秒() {
        assertEquals("1m", fmt(60))
        assertEquals("3m", fmt(180))
        assertEquals("60m", fmt(3600))
    }

    @Test
    fun 分钟加余秒() {
        assertEquals("1m 1s", fmt(61))
        assertEquals("3m 50s", fmt(230))
    }
}

package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.shared.ContextUsageFormatter
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.TokenUsageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话用量展示的纯函数契约：紧凑格式、千分位、上下文占比取整与超窗截断、无数据空显示。
 */
class ContextUsageFormatterUnitTest {

    private val fullUsage = SessionUsageDto(
        tokens = TokenUsageDto(input = 12_300, output = 400, cacheRead = 8_100),
        cost = 0.0123,
        lastStepInputTokens = 480,
        contextWindow = 1_000
    )

    // ==================== 紧凑用量 ====================

    @Test
    fun formatTokensUsesPlainDigitsBelowThousand() {
        assertEquals("847", ContextUsageFormatter.formatTokens(847))
        assertEquals("0", ContextUsageFormatter.formatTokens(0))
    }

    @Test
    fun formatTokensUsesKiloAndMegaSuffix() {
        assertEquals("1.2k", ContextUsageFormatter.formatTokens(1_234))
        assertEquals("12k", ContextUsageFormatter.formatTokens(12_000))
        assertEquals("1.2M", ContextUsageFormatter.formatTokens(1_200_000))
    }

    @Test
    fun formatTokensClampsNegativeToZero() {
        assertEquals("0", ContextUsageFormatter.formatTokens(-5))
    }

    @Test
    fun formatExactUsesThousandSeparator() {
        assertEquals("1,234", ContextUsageFormatter.formatExact(1_234))
        assertEquals("847", ContextUsageFormatter.formatExact(847))
    }

    // ==================== 上下文占比 ====================

    @Test
    fun percentLabelRoundsToInteger() {
        assertEquals("48%", ContextUsageFormatter.percentLabel(480, 1_000))
        assertEquals("50%", ContextUsageFormatter.percentLabel(495, 1_000))
    }

    @Test
    fun percentLabelTruncatesWhenOverWindow() {
        assertEquals("100%", ContextUsageFormatter.percentLabel(1_000, 1_000))
        assertEquals("100%+", ContextUsageFormatter.percentLabel(1_200, 1_000))
    }

    @Test
    fun percentLabelIsNullWhenWindowOrUsedMissing() {
        assertNull("窗口未知时不应展示占比", ContextUsageFormatter.percentLabel(480, null))
        assertNull("无本次请求 input 时不应展示占比", ContextUsageFormatter.percentLabel(null, 1_000))
        assertNull("窗口非法时不应展示占比", ContextUsageFormatter.percentLabel(480, 0))
    }

    // ==================== 摘要与明细 ====================

    @Test
    fun summaryComposesTokensCacheAndContextPercent() {
        assertEquals("↑12.3k ↓400 · 缓存 8.1k · 上下文 48%", ContextUsageFormatter.summary(fullUsage))
    }

    @Test
    fun summaryOmitsPercentWhenWindowUnknown() {
        val usage = fullUsage.copy(contextWindow = null)
        assertEquals("↑12.3k ↓400 · 缓存 8.1k", ContextUsageFormatter.summary(usage))
    }

    @Test
    fun summaryIsEmptyWithoutData() {
        assertEquals("", ContextUsageFormatter.summary(null))
        assertEquals("", ContextUsageFormatter.summary(SessionUsageDto()))
    }

    // ==================== 外层精简档（窄窗口） ====================

    @Test
    fun compactKeepsOnlyInputAndOutput() {
        // 外层只留两项：缓存 / 上下文占比会让底部工具条两端重叠（见 temp/layout-probe/Probe2.java）
        assertEquals("↑12.3k ↓400", ContextUsageFormatter.compact(fullUsage))
    }

    @Test
    fun compactOmitsCostAndReasoningAndContextPercent() {
        val compact = ContextUsageFormatter.compact(fullUsage)
        assertFalse("外层不应出现缓存", compact.contains("缓存"))
        assertFalse("外层不应出现上下文占比", compact.contains("上下文"))
    }

    @Test
    fun compactIsEmptyWithoutTokenData() {
        // 只有上下文占比、没有 token 时外层不展示（避免出现只有占比、没有用量的半截信息）
        val noTokens = SessionUsageDto(lastStepInputTokens = 480, contextWindow = 1_000)
        assertEquals("", ContextUsageFormatter.compact(noTokens))
        assertEquals("", ContextUsageFormatter.compact(null))
        assertEquals("", ContextUsageFormatter.compact(SessionUsageDto()))
    }

    @Test
    fun compactStillReportsOutputWhenInputIsZero() {
        val usage = SessionUsageDto(tokens = TokenUsageDto(output = 69))
        assertEquals("↑0 ↓69", ContextUsageFormatter.compact(usage))
    }

    @Test
    fun detailListsExactCountsAndCost() {
        val detail = ContextUsageFormatter.detail(fullUsage)

        assertTrue("明细应含千分位输入", detail.contains("输入 12,300"))
        assertTrue("明细应含千分位缓存读", detail.contains("缓存读 8,100"))
        assertTrue("明细应含本次请求输入", detail.contains("本次请求输入 480"))
        assertTrue("明细应含上下文窗口", detail.contains("上下文窗口 1,000"))
        assertTrue("明细应含花费", detail.contains("0.0123"))
        assertEquals("无数据时明细应为空", "", ContextUsageFormatter.detail(null))
    }

    @Test
    fun isWarningTriggersFromConfiguredThreshold() {
        assertEquals(80, ContextUsageFormatter.WARN_PERCENT)
        assertTrue("达到阈值应警示", ContextUsageFormatter.isWarning(fullUsage.copy(lastStepInputTokens = 800)))
        assertTrue("超过阈值应警示", ContextUsageFormatter.isWarning(fullUsage.copy(lastStepInputTokens = 1_200)))
        assertFalse("低于阈值不警示", ContextUsageFormatter.isWarning(fullUsage.copy(lastStepInputTokens = 799)))
        assertFalse("窗口未知不警示", ContextUsageFormatter.isWarning(fullUsage.copy(contextWindow = null)))
        assertFalse("无数据不警示", ContextUsageFormatter.isWarning(null))
    }
}
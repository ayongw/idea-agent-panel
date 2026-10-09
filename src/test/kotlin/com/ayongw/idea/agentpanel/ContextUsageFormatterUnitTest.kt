package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.shared.ContextUsageFormatter
import com.ayongw.idea.agentpanel.shared.SessionUsageDto
import com.ayongw.idea.agentpanel.shared.TokenUsageDto
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
    fun compactOnlyKeepsContextPercent() {
        // 底部工具条只留上下文占比：输入/输出在每条助手消息页脚已逐条展示，重复且挤占空间
        assertEquals("48%", ContextUsageFormatter.compact(fullUsage))
    }

    @Test
    fun compactOmitsInputOutputCacheReasoningAndCost() {
        val compact = ContextUsageFormatter.compact(fullUsage)
        assertFalse("不应出现输入箭头", compact.contains("↑"))
        assertFalse("不应出现输出箭头", compact.contains("↓"))
        assertFalse("不应出现缓存", compact.contains("缓存"))
        assertFalse("不应出现花费", compact.contains("花费"))
        assertEquals("只剩占比", "48%", compact)
    }

    @Test
    fun compactDropsPercentWhenContextUnknown() {
        assertEquals("", ContextUsageFormatter.compact(fullUsage.copy(contextWindow = null)))
    }

    @Test
    fun compactIsEmptyWithoutTokenData() {
        assertEquals("", ContextUsageFormatter.compact(null))
        assertEquals("", ContextUsageFormatter.compact(SessionUsageDto()))
    }

    @Test
    fun compactKeepsPercentEvenWithoutAggregateTokens() {
        // 只有占比、没有累计 token 时仍要展示占比 —— 外层现在**就是**占比的载体，
        // 若再要求有 token 才显示，用户会看不到上下文余量。
        val noTokens = SessionUsageDto(lastStepInputTokens = 480, contextWindow = 1_000)
        assertEquals("48%", ContextUsageFormatter.compact(noTokens))
    }

    @Test
    fun detailUsesAbbreviatedCountsAndCost() {
        // 明细与外层统一用缩写：一长串精确数字读起来费劲，这层只需量级感知
        val detail = ContextUsageFormatter.detail(fullUsage)

        assertTrue("输入应缩写：$detail", detail.contains("输入 12.3k"))
        assertTrue("缓存读应缩写：$detail", detail.contains("缓存读 8.1k"))
        assertTrue("本次请求输入应缩写：$detail", detail.contains("本次请求输入 480"))
        assertTrue("上下文窗口应缩写：$detail", detail.contains("上下文窗口 1k"))
        assertTrue("明细应含花费", detail.contains("0.0123"))
        assertEquals("无数据时明细应为空", "", ContextUsageFormatter.detail(null))
    }

    @Test
    fun detailHasNoThousandSeparator() {
        // 回归：曾用千分位精确值（输入 14,224 / 上下文窗口 262,144），tooltip 一行太长
        val big = SessionUsageDto(
            tokens = TokenUsageDto(input = 14_224, output = 51, cacheRead = 512),
            lastStepInputTokens = 14_224,
            contextWindow = 262_144
        )
        val detail = ContextUsageFormatter.detail(big)
        listOf("14,224", "262,144", "14,267").forEach {
            assertFalse("明细不应出现千分位 $it：$detail", detail.contains(it))
        }
        assertTrue("应显示缩写：$detail", detail.contains("输入 14.2k"))
        assertTrue("应显示缩写：$detail", detail.contains("上下文窗口 262.1k"))
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
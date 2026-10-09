package com.ayongw.idea.opencode.shared

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 会话 token 用量，对应 opencode v2 `TokenUsage.Info`
 * （`{input, output, reasoning, cache:{read, write}}`）
 */
@Serializable
data class TokenUsageDto(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cacheRead: Long = 0L,
    val cacheWrite: Long = 0L
)

/**
 * 当前会话的用量快照，供输入框下方的指示器展示。
 *
 * - [tokens] / [cost]：会话累计用量（`Session.Info.tokens` / `cost`）
 * - [lastStepInputTokens]：最近一次 step 的 input tokens，作为上下文占用的**分子**
 * - [contextWindow]：当前会话模型的上下文窗口（`Model.Info.limit.context`），作为**分母**；
 *   模型未知或未匹配到时为 null，此时不展示占比
 */
@Serializable
data class SessionUsageDto(
    val tokens: TokenUsageDto = TokenUsageDto(),
    val cost: Double? = null,
    val lastStepInputTokens: Long? = null,
    val contextWindow: Long? = null
)

/**
 * 用量的文本格式与占比计算（纯函数，便于单测）。
 *
 * 占比口径：`最近一次 step 的 input tokens ÷ 模型上下文窗口`。
 * 不能用会话累计 `tokens.input` 当分子 —— 那是多轮累计值。
 */
object ContextUsageFormatter {

    /** 上下文占用警示阈值（百分比） */
    const val WARN_PERCENT = 80

    /** 紧凑用量：`1_200` → `1.2k`，`1_200_000` → `1.2M`，`847` → `847` */
    fun formatTokens(value: Long): String {
        val v = value.coerceAtLeast(0L)
        return when {
            v >= 1_000_000L -> trimTrailingZero(v / 1_000_000.0) + "M"
            v >= 1_000L -> trimTrailingZero(v / 1_000.0) + "k"
            else -> v.toString()
        }
    }

    /** 精确用量（千分位）：`1234` → `1,234` */
    fun formatExact(value: Long): String =
        String.format(Locale.US, "%,d", value.coerceAtLeast(0L))

    /**
     * 上下文占比：窗口未知或非法返回 null；超出窗口显示 `100%+`；其余四舍五入取整，如 `48%`
     */
    fun percentLabel(usedTokens: Long?, contextWindow: Long?): String? {
        if (usedTokens == null || contextWindow == null || contextWindow <= 0L) return null
        val used = usedTokens.coerceAtLeast(0L)
        if (used > contextWindow) return "100%+"
        return "${Math.round(used * 100.0 / contextWindow)}%"
    }

    /**
     * 外层行内摘要（窄）：`↑12.3k ↓0.4k`
     *
     * 缓存 / 上下文占比 / 推理等不进外层（宽度不可退让，窄窗口下会与模型名重叠），
     * 全部信息由 [detail] 以悬浮明细承载，不丢字段。
     */
    fun compact(usage: SessionUsageDto?): String {
        if (usage == null) return ""
        val tokens = usage.tokens
        if (tokens == TokenUsageDto()) return ""
        return "↑${formatTokens(tokens.input)} ↓${formatTokens(tokens.output)}"
    }

    /** 行内摘要：`↑12.3k ↓0.4k · 缓存 8.1k · 上下文 48%`；无数据返回空串（由调用方隐藏） */
    fun summary(usage: SessionUsageDto?): String {
        if (usage == null) return ""
        val tokens = usage.tokens
        val parts = mutableListOf<String>()
        if (tokens != TokenUsageDto()) {
            parts += "↑${formatTokens(tokens.input)} ↓${formatTokens(tokens.output)}"
            val cache = tokens.cacheRead + tokens.cacheWrite
            if (cache > 0L) parts += "缓存 ${formatTokens(cache)}"
        }
        percentLabel(usage.lastStepInputTokens, usage.contextWindow)?.let { parts += "上下文 $it" }
        return parts.joinToString(" · ")
    }

    /** 悬浮明细（多行）；无数据返回空串 */
    fun detail(usage: SessionUsageDto?): String {
        if (usage == null) return ""
        val tokens = usage.tokens
        val lines = mutableListOf(
            "输入 ${formatExact(tokens.input)}",
            "输出 ${formatExact(tokens.output)}"
        )
        if (tokens.reasoning > 0L) lines += "推理 ${formatExact(tokens.reasoning)}"
        lines += "缓存读 ${formatExact(tokens.cacheRead)}"
        lines += "缓存写 ${formatExact(tokens.cacheWrite)}"
        usage.lastStepInputTokens?.let { lines += "本次请求输入 ${formatExact(it)}" }
        usage.contextWindow?.takeIf { it > 0L }?.let { lines += "上下文窗口 ${formatExact(it)}" }
        usage.cost?.let { lines += String.format(Locale.US, "花费 \$%.4f", it) }
        return lines.joinToString("\n")
    }

    /** 是否达到上下文占用警示阈值 */
    fun isWarning(usage: SessionUsageDto?): Boolean {
        val used = usage?.lastStepInputTokens ?: return false
        val window = usage.contextWindow ?: return false
        if (window <= 0L) return false
        return used * 100L >= window * WARN_PERCENT
    }

    private fun trimTrailingZero(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
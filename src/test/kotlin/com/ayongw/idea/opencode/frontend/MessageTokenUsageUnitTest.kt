package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble
import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.TokenUsageRow
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.TokenUsageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手消息末尾 token 行（每条消息的本次用量）。
 *
 * 覆盖两个关键行为：
 * - 有用量时渲染 `↑input ↓output · 缓存 x`，无用量时自身隐藏（不占高）；
 * - **正文内容不变、仅用量到达时也必须刷新** —— 这是回归重点：
 *   流式期间气泡已建好，终态对账补来的 tokens 若不进 `signatureOf`，`syncWith`
 *   会 early-return，token 行永远不出现。
 */
class MessageTokenUsageUnitTest {

    private fun assistant(
        content: String = "答案",
        usage: TokenUsageDto? = null,
        cost: Double? = null
    ) = ChatMessage(
        id = "msg_1",
        content = content,
        author = "opencode",
        isMyMessage = false,
        usage = usage,
        costUsd = cost
    )

    private fun tokenRow(bubble: MessageBubble): TokenUsageRow? =
        bubble.components.filterIsInstance<TokenUsageRow>().firstOrNull()

    private fun rowText(bubble: MessageBubble): String {
        val row = tokenRow(bubble)
        assertNotNull("助手正文气泡应带 token 行组件", row)
        return row!!.components.filterIsInstance<javax.swing.JLabel>()
            .firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
    }

    @Test
    fun 有用量时渲染本次token摘要() {
        val bubble = MessageBubble(
            assistant(usage = TokenUsageDto(input = 11_300, output = 69, cacheRead = 18_500))
        )

        assertEquals("↑11.3k ↓69 · 缓存 18.5k", rowText(bubble))
        assertTrue("有用量时应可见", tokenRow(bubble)!!.isVisible)
    }

    @Test
    fun 无用量时隐藏且不占高度() {
        val bubble = MessageBubble(assistant())

        assertEquals("无用量时不应有任何文本", "", rowText(bubble))
        assertFalse("无用量时整体隐藏", tokenRow(bubble)!!.isVisible)
    }

    @Test
    fun 仅有花费无token时仍隐藏() {
        // summary() 以 token 为口径；只有 cost 时外层不展示（避免半截信息），明细里仍有花费
        val bubble = MessageBubble(assistant(cost = 0.0123))

        assertFalse("仅花费不应展示外层 token 行", tokenRow(bubble)!!.isVisible)
    }

    @Test
    fun 用户消息不展示token行() {
        val bubble = MessageBubble(
            ChatMessage(
                id = "u1",
                content = "你好",
                author = "Me",
                isMyMessage = true,
                usage = TokenUsageDto(input = 100, output = 0)
            )
        )

        assertFalse("用户消息无 token 行", tokenRow(bubble)!!.isVisible)
    }

    @Test
    fun 内容不变仅用量到达时刷新token行() {
        // 模拟真实时序：流式期先建气泡（无用量）→ 终态对账补齐 tokens，正文未变
        val bubble = MessageBubble(assistant(content = "答案"))
        assertFalse("初始无用量应隐藏", tokenRow(bubble)!!.isVisible)

        bubble.syncWith(assistant(content = "答案", usage = TokenUsageDto(input = 480, output = 20)))

        assertEquals("usage 到达后应刷新 token 行", "↑480 ↓20", rowText(bubble))
        assertTrue("刷新后应可见", tokenRow(bubble)!!.isVisible)
    }

    @Test
    fun 用量变化也会刷新() {
        val bubble = MessageBubble(assistant(usage = TokenUsageDto(input = 100, output = 10)))

        bubble.syncWith(assistant(usage = TokenUsageDto(input = 200, output = 20)))

        assertEquals("↑200 ↓20", rowText(bubble))
    }

    @Test
    fun 内容变化仍正常更新且token行保持() {
        val bubble = MessageBubble(assistant(content = "答", usage = TokenUsageDto(input = 1, output = 1)))

        bubble.syncWith(assistant(content = "答案", usage = TokenUsageDto(input = 1, output = 1)))

        assertEquals("正文更新不应丢失 token 行", "↑1 ↓1", rowText(bubble))
    }

    @Test
    fun 悬浮明细含推理缓存与花费() {
        val bubble = MessageBubble(
            assistant(
                usage = TokenUsageDto(input = 480, output = 69, reasoning = 25, cacheRead = 8100, cacheWrite = 120),
                cost = 0.0123
            )
        )
        val row = tokenRow(bubble)!!
        val tooltip = row.components.filterIsInstance<javax.swing.JLabel>()
            .first { it.text.isNotBlank() }.toolTipText

        assertNotNull("有 token 行时应带悬浮明细", tooltip)
        assertTrue("明细应含推理", tooltip!!.contains("推理 25"))
        assertTrue("明细应含缓存读", tooltip.contains("缓存读 8,100"))
        assertTrue("明细应含缓存写", tooltip.contains("缓存写 120"))
        assertTrue("明细应含花费", tooltip.contains("0.0123"))
    }
}
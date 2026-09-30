package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.BackendChatRepositoryModel
import com.ayongw.idea.opencode.shared.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * 对账增量合并（TSD-30 §4.2 C-增量 / 对账交互流畅、消息不乱丢）
 *
 * 语义：REST 权威覆盖同 id 内容、本地独有保留、REST 缺失的按时间整段补充。
 */
class BackendChatRepositoryModelMergeUnitTest {

    private fun msg(id: String, content: String, time: LocalDateTime, type: ChatMessage.ChatMessageType = ChatMessage.ChatMessageType.TEXT) =
        ChatMessage(id = id, content = content, author = "AI", timestamp = time, type = type)

    private val t1 = LocalDateTime.of(2026, 9, 30, 10, 0, 0)
    private val t2 = t1.plusSeconds(30)
    private val t3 = t1.plusSeconds(60)

    @Test
    fun `REST 覆盖同 id 内容，本地独有保留`() {
        // 本地：正文 + 思考（REST 尚未落库思考）
        val existing = listOf(
            msg("msg_1", "本地正文", t1),
            msg("msg_1#reasoning", "本地思考", t1),
        )
        // REST：正文更新了内容，无思考
        val rest = listOf(msg("msg_1", "REST 权威正文", t1))

        val merged = BackendChatRepositoryModel.mergeReconcile(existing, rest)

        assertEquals("正文被 REST 覆盖", "REST 权威正文", merged[0].content)
        assertEquals("思考保留不丢", "本地思考", merged[1].content)
        assertEquals(2, merged.size)
    }

    @Test
    fun `断线遗漏的消息按时间整段补充到正确位置`() {
        // 本地只有上一轮（t1）
        val existing = listOf(msg("msg_0", "上一轮", t1))
        // REST 返回两轮：上一轮(t1) + 断线期间的下一轮(t2: thinking+text)
        val rest = listOf(
            msg("msg_0", "上一轮", t1),
            msg("msg_2#reasoning", "断线思考", t2),
            msg("msg_2", "断线正文", t2),
        )

        val merged = BackendChatRepositoryModel.mergeReconcile(existing, rest)

        assertEquals("断线轮整段插入且顺序正确", listOf("msg_0", "msg_2#reasoning", "msg_2"), merged.map { it.id })
    }

    @Test
    fun `REST 空时保留本地不整屏清空`() {
        val existing = listOf(msg("msg_1", "内容", t1))
        val merged = BackendChatRepositoryModel.mergeReconcile(existing, emptyList())
        assertEquals("REST 空不清空本地", existing, merged)
    }

    @Test
    fun `本地空时直接用 REST`() {
        val rest = listOf(msg("msg_1", "内容", t1))
        assertEquals(rest, BackendChatRepositoryModel.mergeReconcile(emptyList(), rest))
    }

    @Test
    fun `同轮思考与正文同时间戳时不把思考插到正文之后`() {
        // 本地：上一轮结束；断线轮思考与正文同 createdMillis（REST parts 顺序：思考在前）
        val existing = listOf(msg("msg_0", "上一轮", t1))
        val rest = listOf(
            msg("msg_0", "上一轮", t1),
            msg("msg_3#reasoning", "断线思考", t2),
            msg("msg_3", "断线正文", t2),
        )

        val merged = BackendChatRepositoryModel.mergeReconcile(existing, rest)

        // 以 REST 的 parts 顺序（思考在前）整段插入，不因同时间戳错位
        assertEquals(listOf("msg_0", "msg_3#reasoning", "msg_3"), merged.map { it.id })
        assertEquals("断线思考", merged[1].content)
    }

    @Test
    fun `工具中间态被 REST 终态覆盖`() {
        val existing = listOf(
            msg("call_1", "", t1, ChatMessage.ChatMessageType.TOOL),
            msg("msg_1", "正文", t1),
        )
        val rest = listOf(msg("msg_1", "正文终态", t1))

        val merged = BackendChatRepositoryModel.mergeReconcile(existing, rest)

        assertEquals("工具中间态保留（REST 无则不动）", ChatMessage.ChatMessageType.TOOL, merged[0].type)
        assertEquals("正文被覆盖为终态", "正文终态", merged[1].content)
    }
}

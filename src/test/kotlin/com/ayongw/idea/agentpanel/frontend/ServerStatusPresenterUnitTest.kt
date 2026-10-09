package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.statusBar.ServerStatusPresenter
import com.ayongw.idea.agentpanel.shared.ServerStateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏 Server 状态的展示映射契约。
 *
 * 覆盖全部状态枚举 + 失败分类兜底：状态栏是「工具窗关闭时唯一的健康信号」，
 * 任何状态映射错都会让用户被误导（以为服务可用其实已挂）。
 */
class ServerStatusPresenterUnitTest {

    /** 固定文案，避免依赖 bundle 初始化 */
    private val strings = object : ServerStatusPresenter.Strings {
        override fun ready(state: ServerStateDto) = "READY:${state.baseUrl}:${state.port}"
        override fun reusing(state: ServerStateDto) = "REUSING:${state.baseUrl}:${state.port}"
        override fun progress(state: ServerStateDto) = "PROGRESS:${state.state}:${state.port}"
        override fun needsCredentials() = "NEEDS_CREDENTIALS"
        override fun failed(state: ServerStateDto) = "FAILED:${state.failure}:${state.detail}"
        override fun idle() = "IDLE"
    }

    private fun present(state: String) = ServerStatusPresenter.present(ServerStateDto(state = state), strings)

    private fun failed(failure: String?, detail: String? = null) =
        ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_FAILED, failure = failure, detail = detail),
            strings
        )

    @Test
    fun READY是唯一非进展非异常态() {
        val p = ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_READY, baseUrl = "http://127.0.0.1:4096", port = 4096),
            strings
        )
        assertFalse("READY 不应算异常", p.attention)
        assertEquals("文案应带端点与端口", "READY:http://127.0.0.1:4096:4096", p.tooltip)
        assertNotNull("应有图标", p.icon)
    }

    @Test
    fun 进行中各状态归为进展() {
        // 注意：REUSING 不在此列 —— 复用外部实例是终态健康，不是进行中（见 reusing 归类测试）
        listOf(
            ServerStateDto.STATE_DISCOVERING,
            ServerStateDto.STATE_STARTING,
            ServerStateDto.STATE_STOPPING
        ).forEach { state ->
            val p = present(state)
            assertFalse("$state 不应算异常", p.attention)
            assertTrue("$state 应带进度标记", p.summary.startsWith("PROGRESS:$state"))
        }
    }

    @Test
    fun 复用外部实例算就绪而非进行中() {
        // 回归：外部实例（用户自己起的 opencode serve）校验通过后停在 REUSING，
        // 此前被归到 progress 分支 → 状态栏永远显示「Detecting...」
        val p = ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_REUSING, baseUrl = "http://127.0.0.1:4096", port = 4096),
            strings
        )
        assertFalse("复用实例是健康态，不该引起注意", p.attention)
        assertNull("健康态不该有警示色", p.color)
        assertEquals("文案应走 reusing", "REUSING:http://127.0.0.1:4096:4096", p.tooltip)
    }

    @Test
    fun 需凭据是异常态() {
        val p = present(ServerStateDto.STATE_NEEDS_CREDENTIALS)
        assertTrue("需凭据必须引起注意", p.attention)
        assertNotNull("异常态应有警示色", p.color)
    }

    @Test
    fun 失败是异常态且带失败分类() {
        val p = failed("AUTH_FAILED", "401")
        assertTrue("失败必须引起注意", p.attention)
        assertEquals("文案应含失败分类与详情", "FAILED:AUTH_FAILED:401", p.summary)
    }

    @Test
    fun 失败分类缺失时兜底为不可达() {
        // 后端枚举未来新增分类时，前端不至于显示空白
        val p = failed(null)
        assertTrue(p.attention)
        assertEquals("FAILED:null:null", p.summary)
    }

    @Test
    fun IDLE与STOPPED为中性态() {
        listOf(ServerStateDto.STATE_IDLE, ServerStateDto.STATE_STOPPED).forEach { state ->
            val p = present(state)
            assertFalse("$state 不应算异常", p.attention)
            assertEquals("$state 文案", "IDLE", p.summary)
        }
    }

    @Test
    fun 未知状态按中性处理不抛异常() {
        // 平台/后端版本错配时新增了未知状态，不能让状态栏崩
        val p = present("SOMETHING_NEW")
        assertFalse("未知状态不应误报异常", p.attention)
        assertNotNull("未知状态仍需有图标", p.icon)
    }

    @Test
    fun 同一状态不同端点时文案随之变化() {
        val a = ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_READY, port = 4096), strings
        )
        val b = ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_READY, port = 4097), strings
        )
        assertFalse("端点不同时文案应不同（便于状态栏 tooltip 区分）", a.tooltip == b.tooltip)
    }

    @Test
    fun STARTING使用端口信息() {
        val p = ServerStatusPresenter.present(
            ServerStateDto(state = ServerStateDto.STATE_STARTING, port = 4097), strings
        )
        assertTrue("STARTING 文案应含端口", p.summary.endsWith(":4097"))
    }
}
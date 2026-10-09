package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.SessionListView
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.resolveSessionListView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话列表视图选择契约。
 *
 * 关键一条：**加载中不能渲染成空列表**。`allSessionsFlow` 初始值就是空列表，
 * 与「拉到了但确实没有会话」不可区分；首次打开「全部会话」时若直接给空态，
 * 用户会读成「这个工作区没有会话」，而实际只是还没拉完 —— 表现为「打开是空的」。
 */
class SessionListViewUnitTest {

    @Test
    fun 有会话时展示列表() {
        assertEquals(SessionListView.LIST, resolveSessionListView(hasRows = true, loading = false))
    }

    @Test
    fun 加载中且无数据时展示加载态而非空态() {
        assertEquals(SessionListView.LOADING, resolveSessionListView(hasRows = false, loading = true))
    }

    @Test
    fun 加载完成且确实无会话时展示空态() {
        assertEquals(SessionListView.EMPTY, resolveSessionListView(hasRows = false, loading = false))
    }

    @Test
    fun 加载中但已有数据时仍展示列表() {
        // 刷新场景（弹窗二次打开 / 删除后重拉）：旧数据先顶上，不能闪成 loading
        assertEquals(SessionListView.LIST, resolveSessionListView(hasRows = true, loading = true))
    }
}

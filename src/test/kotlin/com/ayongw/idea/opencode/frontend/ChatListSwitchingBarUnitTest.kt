package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ChatList
import com.ayongw.idea.opencode.shared.ChatMessage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Container
import java.nio.file.Files
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * 切换会话加载提示条（TSD-34 §4 / 方案 A）。
 *
 * 契约：
 * - 默认不可见，不占布局高度
 * - `setSwitching` 幂等：重复同值不重复触发布局
 * - **不替换消息区卡片**：旧消息留在原位（切换失败刻意不清空），只多一条顶部细线
 */
class ChatListSwitchingBarUnitTest {

    private lateinit var project: Project
    private lateinit var chatList: ChatList

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("switching-bar", Files.createTempDirectory("switching-bar").toString())
        chatList = ChatList(project)
    }

    @After
    fun tearDown() {
        chatList.dispose()
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    private fun switchingBar(): JProgressBar {
        fun walk(c: Container): JProgressBar? {
            if (c is JProgressBar) return c
            c.components.forEach { child ->
                if (child is Container) walk(child)?.let { return it }
            }
            return null
        }
        return walk(chatList) ?: error("未找到切换加载提示条")
    }

    private fun messages() = listOf(
        ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
        ChatMessage(id = "a1", content = "回复", author = "opencode")
    )

    @Test
    fun 默认不显示且不占高度() {
        val bar = switchingBar()
        assertFalse("默认不应显示", bar.isVisible)
        assertEquals("不可见时高度应为 0", 0, bar.preferredSize.height.coerceAtMost(0))
    }

    @Test
    fun 开启后可见且为不确定进度() {
        SwingUtilities.invokeAndWait { chatList.setSwitching(true) }

        val bar = switchingBar()
        assertTrue("切换中应显示提示条", bar.isVisible)
        assertTrue("应为不确定进度（不知道剩余时间）", bar.isIndeterminate)
    }

    @Test
    fun 关闭后恢复不可见() {
        SwingUtilities.invokeAndWait {
            chatList.setSwitching(true)
            chatList.setSwitching(false)
        }
        assertFalse("切换结束应隐藏提示条", switchingBar().isVisible)
    }

    @Test
    fun 重复设置同值不抛异常且状态一致() {
        SwingUtilities.invokeAndWait {
            repeat(3) { chatList.setSwitching(true) }
            assertTrue(switchingBar().isVisible)
            repeat(3) { chatList.setSwitching(false) }
            assertFalse(switchingBar().isVisible)
        }
    }

    @Test
    fun 切换期间消息不被清空() {
        // 切换失败时后端刻意不清空旧消息（保证数据与当前会话一致），
        // 因此提示条只做"加一条线"，绝不能把消息区换成空态/骨架。
        SwingUtilities.invokeAndWait {
            chatList.setSize(600, 400)
            chatList.setMessages(messages())
            chatList.setSwitching(true)
        }

        val bubbles = allBubbles()
        assertEquals("切换中消息应仍在容器内", 2, bubbles)
        assertTrue("切换中提示条可见", switchingBar().isVisible)
    }

    private fun allBubbles(): Int {
        fun walk(c: Container): Int {
            val self = if (c.javaClass.simpleName == "MessageBubble") 1 else 0
            return self + c.components.filterIsInstance<Container>().sumOf { walk(it) }
        }
        return walk(chatList)
    }
}
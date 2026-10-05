package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ChatList
import com.ayongw.idea.opencode.frontend.chatApp.ui.ListUpdateCoalescer
import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble
import com.ayongw.idea.opencode.shared.ChatMessage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Container
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.Box
import javax.swing.SwingUtilities

/**
 * 消息列表布局冒烟（TSD-30 §5.7 Swing 层）
 *
 * 断言：气泡挂载顺序与模型一致（gridy 派生）、删除中间消息后重排正确、filler 显式引用在末位、
 * 布局后无零尺寸可见气泡（§5.7 必过项）。
 */
class ChatListSwingSmokeUnitTest {

    private lateinit var project: Project
    private lateinit var chatList: ChatList

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("chatlist-smoke", Files.createTempDirectory("chatlist-smoke").toString())
        chatList = ChatList(project)
    }

    @After
    fun tearDown() {
        chatList.dispose()
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    @Test
    fun 消息按模型顺序挂载且filler在末位() {
        // setMessages 主体同步执行；滚动回调经 invokeLater 进 AWT 队列，不影响顺序断言
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a1", content = "回复一", author = "opencode"),
                ChatMessage(id = "a2", content = "回复二", author = "opencode"),
            )
        )

        val container = gridBagContainer(chatList) ?: error("未找到消息容器（GridBagLayout）")
        val bubbleIds = container.components
            .filterIsInstance<MessageBubble>()
            .map { it.messageId }
        assertEquals("气泡挂载顺序应与模型一致", listOf("u1", "a1", "a2"), bubbleIds)
        assertTrue(
            "末位应为 filler（Box.Filler），且只有一个",
            container.components.last() is Box.Filler && container.components.count { it is Box.Filler } == 1
        )
    }

    @Test
    fun 删除中间消息后按新顺序重排() {
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a1", content = "被删的回复", author = "opencode"),
                ChatMessage(id = "a2", content = "保留的回复", author = "opencode"),
            )
        )

        // 删除中间的 a1（对账后服务端不再返回）
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a2", content = "保留的回复", author = "opencode"),
            )
        )

        val container = gridBagContainer(chatList) ?: error("未找到消息容器（GridBagLayout）")
        val bubbleIds = container.components
            .filterIsInstance<MessageBubble>()
            .map { it.messageId }
        assertEquals("删除后顺序应收敛为新模型顺序", listOf("u1", "a2"), bubbleIds)
        assertTrue("不应残留被删气泡", container.components.filterIsInstance<MessageBubble>().none { it.messageId == "a1" })
    }

    @Test
    fun 流式内容更新不改变挂载顺序() {
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a1", content = "旧内容", author = "opencode"),
            )
        )

        // 流式更新：内容变、id 集合不变（集合不重挂，只就地更新）
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a1", content = "新内容", author = "opencode"),
            )
        )

        val container = gridBagContainer(chatList) ?: error("未找到消息容器（GridBagLayout）")
        val bubbleIds = container.components
            .filterIsInstance<MessageBubble>()
            .map { it.messageId }
        assertEquals("流式内容更新后顺序保持不变", listOf("u1", "a1"), bubbleIds)
    }

    /** 深度优先找「承载气泡或 filler 的」GridBagLayout 容器（跳过空的占位面板） */
    private fun gridBagContainer(root: Container): Container? {
        if (root.layout is GridBagLayout && root.components.any { it is MessageBubble || it is Box.Filler }) {
            return root
        }
        root.components.forEach { child ->
            if (child is Container) {
                gridBagContainer(child)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun 布局后无零尺寸可见气泡() {
        // 测试 JVM 为 headless，不能建 JFrame：手动 setSize + 递归 doLayout 让消息容器拿到真实宽度
        SwingUtilities.invokeAndWait {
            chatList.setSize(600, 400)
            chatList.setMessages(messages())
            layoutTree(chatList)
        }
        // 越过 ListUpdateCoalescer 合并窗口（20ms），使下一次 setMessages 放行一次完整布局
        Thread.sleep(30)
        SwingUtilities.invokeAndWait {
            chatList.setMessages(messages())
        }
        // 二次 setMessages 放行 requestLayout，ensureLaidOut 在容器宽度 > 0 下真正执行；
        // §5.7 断言：布局后所有已挂载气泡不得为零尺寸（首帧容器 0 高缺陷的回归防护）
        val bubbles = gridBagContainer(chatList)
            ?.components
            ?.filterIsInstance<MessageBubble>()
            ?: emptyList()
        assertEquals("消息容器应挂载 3 个气泡", 3, bubbles.size)
        bubbles.forEach { bubble ->
            assertTrue(
                "可见气泡不应为零尺寸：${bubble.messageId} = ${bubble.width}x${bubble.height}",
                bubble.width > 0 && bubble.height > 0
            )
        }
    }

    private fun messages() = listOf(
        ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
        ChatMessage(id = "a1", content = "第一条助手回复，内容足够长以撑出可布局高度", author = "opencode"),
        ChatMessage(id = "a2", content = "第二条助手回复，用于验证布局后的气泡尺寸", author = "opencode"),
    )

    @Test
    fun 结构性变化不被合并窗口吞掉() {
        // 合并窗口放大到 10s：第二次 setMessages 必然落在窗口内（不依赖真实耗时，时序稳定）
        chatList.dispose()
        chatList = ChatList(project, ListUpdateCoalescer(windowMs = 10_000))

        SwingUtilities.invokeAndWait {
            chatList.setSize(600, 400)
            chatList.setMessages(listOf(messages().first()))
            layoutTree(chatList) // 建立容器宽度，使 ensureLaidOut 不因 width<=0 早退
        }

        // 结构性新增（id 集合变化）：removeAll 重挂后必须立即布局，不能被 leading-edge 节流丢弃
        SwingUtilities.invokeAndWait {
            chatList.setMessages(messages())
        }

        val bubbles = gridBagContainer(chatList)
            ?.components
            ?.filterIsInstance<MessageBubble>()
            ?: emptyList()
        assertEquals("消息容器应挂载 3 个气泡", 3, bubbles.size)
        bubbles.forEach { bubble ->
            assertTrue(
                "结构性变化后气泡不应停留在 0x0（视口整屏空白）：${bubble.messageId} = ${bubble.width}x${bubble.height}",
                bubble.width > 0 && bubble.height > 0
            )
        }
    }

    /** 深度优先 doLayout（headless 下替代真实窗口校验，让每个容器拿到父级分配的尺寸） */
    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { child ->
            if (child is Container) layoutTree(child)
        }
    }
}

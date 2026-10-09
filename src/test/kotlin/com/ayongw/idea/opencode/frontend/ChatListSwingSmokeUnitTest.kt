package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ChatList
import com.ayongw.idea.opencode.frontend.chatApp.ui.ListUpdateCoalescer
import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.Box
import javax.swing.JTextArea
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
        // 相邻的两条助手回复按对话轮次合并为一个回复主题（key=组内首条 a1）
        assertEquals("气泡挂载顺序应与轮次模型一致", listOf("u1", "a1"), bubbleIds)
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

        // 删除中间的 a1（对账后服务端不再返回）：剩余 a2 成为轮次首条，组 key 变为 a2
        chatList.setMessages(
            listOf(
                ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                ChatMessage(id = "a2", content = "保留的回复", author = "opencode"),
            )
        )

        val container = gridBagContainer(chatList) ?: error("未找到消息容器（GridBagLayout）")
        val bubbles = container.components.filterIsInstance<MessageBubble>()
        assertEquals("删除后顺序应收敛为新轮次模型", listOf("u1", "a2"), bubbles.map { it.messageId })
        assertTrue("不应残留被删气泡", bubbles.none { it.containsId("a1") })
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
        assertEquals("消息容器应挂载 2 个轮次气泡（用户 + 合并后的助手主题）", 2, bubbles.size)
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
    fun 超长正文按视口宽度换行不撑宽不隐藏() {
        // 用户原则：面板宽度=消息最大宽度，超宽先换行/自适应，横向滚动兜底，永不隐藏。
        // 300 个中文字无换行：逐行 JBLabel 时代 preferred 宽 ~4000px，超出部分被裁断看不全
        SwingUtilities.invokeAndWait {
            chatList.setSize(300, 3000)
            // 先布局空壳：让视口把真实宽度分给消息容器，使 setMessages 内
            // ensureLaidOut 不因 width<=0 早退（width=0 时换行 View 无宽度可同步）
            layoutTree(chatList)
            // 用户原则：面板宽度=消息最大宽度，超宽先换行/自适应，横向滚动兜底，永不隐藏。
            // 300 个中文字无换行：逐行 JBLabel 时代 preferred 宽 ~4000px，超出部分被裁断看不全
            chatList.setMessages(
                listOf(
                    ChatMessage(id = "u1", content = "你好", author = "me", isMyMessage = true),
                    ChatMessage(id = "a1", content = "测".repeat(300), author = "AI Buddy"),
                )
            )
            // 最终几何布局
            layoutTree(chatList)
        }

        val wrapAreas = mutableListOf<JTextArea>()
        val overWidth = mutableListOf<String>()
        fun scan(component: Component) {
            if (component is JTextArea && component.lineWrap) wrapAreas += component
            if (component.width > 300) {
                val text = (component as? javax.swing.JLabel)?.text?.take(20)
                overWidth += "${component::class.java.simpleName}(${component.width}x${component.height}) " +
                    "text=[$text] parent=${component.parent?.let { it::class.java.simpleName }}/${component.parent?.width}"
            }
            if (component is Container) component.components.forEach(::scan)
        }
        // 只扫描消息容器子树：空占位卡片（"Start a conversation"）虽不可见但仍有陈旧 bounds，与显示无关
        val messagesContainer = gridBagContainer(chatList) ?: error("未找到消息容器")
        messagesContainer.components.forEach(::scan)

        val area = wrapAreas.maxByOrNull { it.text.length }
            ?: error("未找到换行正文组件（正文未做宽度自适应）")
        assertTrue("超宽组件：$overWidth", overWidth.isEmpty())
        val lineHeight = area.getFontMetrics(area.font).height
        assertTrue("长文应折成多行可见（area=${area.width}x${area.height}）", area.height > lineHeight * 3)
    }

    @Test
    fun 多轮思考统一在一个回复主题的思考块内() {
        val user = ChatMessage(id = "u1", content = "当前项目进行到什么阶段了？", author = "me", isMyMessage = true)
        val reasoning1 = ChatMessage(
            id = "msgA#reasoning",
            content = "第一轮思考：先检索项目记忆与最近提交",
            author = "AI Buddy",
            type = ChatMessage.ChatMessageType.AI_THINKING
        )
        val tool1 = ChatMessage(
            id = "call_execute_1",
            content = "execute memory_search",
            author = "AI Buddy",
            type = ChatMessage.ChatMessageType.TOOL,
            tool = ToolCallDto(
                callId = "call_execute_1",
                name = "execute",
                input = "memory_search",
                output = "命中 24 条记忆",
                status = ToolCallStatus.COMPLETED
            )
        )
        val tool2 = ChatMessage(
            id = "call_shell_1",
            content = "shell git status",
            author = "AI Buddy",
            type = ChatMessage.ChatMessageType.TOOL,
            tool = ToolCallDto(
                callId = "call_shell_1",
                name = "shell",
                input = "git status --short && git log --oneline -15",
                output = "10 条提交记录",
                status = ToolCallStatus.COMPLETED,
                exit = 0
            )
        )
        val reasoning2 = ChatMessage(
            id = "msgB#reasoning",
            content = "第二轮思考：结合日志判断当前处于面板整体优化阶段",
            author = "AI Buddy",
            type = ChatMessage.ChatMessageType.AI_THINKING
        )
        val answer = ChatMessage(
            id = "msgB",
            content = "项目当前处于面板整体优化阶段",
            author = "AI Buddy"
        )

        // 分阶段推送，覆盖流式并入路径（先只有用户 + 第一轮思考，再补齐工具/二轮思考/正文）
        SwingUtilities.invokeAndWait {
            chatList.setSize(400, 3000)
            layoutTree(chatList)
            chatList.setMessages(listOf(user, reasoning1))
        }
        Thread.sleep(30) // 越过合并窗口
        SwingUtilities.invokeAndWait {
            chatList.setMessages(listOf(user, reasoning1, tool1, tool2, reasoning2, answer))
            layoutTree(chatList)
        }

        val bubbles = gridBagContainer(chatList)
            ?.components
            ?.filterIsInstance<MessageBubble>()
            ?: emptyList()
        assertEquals("一次提问应只有一个用户气泡 + 一个助手回复主题", 2, bubbles.size)
        val turn = bubbles.first { !it.isMy }
        // 多轮思考与工具卡片全部并入同一回复主题
        listOf("msgA#reasoning", "call_execute_1", "call_shell_1", "msgB#reasoning", "msgB").forEach { id ->
            assertTrue("回复主题应包含 $id", turn.containsId(id))
        }

        // 子树统计：恰好一个思考块、恰好两张工具卡片
        var reasoningSections = 0
        var toolCards = 0
        val allTexts = mutableListOf<String>()
        fun collect(component: Component) {
            when (component::class.java.simpleName) {
                "ReasoningSection" -> reasoningSections++
                "ToolCallCard" -> toolCards++
            }
            if (component is JTextArea) allTexts += component.text
            if (component is Container) component.components.forEach(::collect)
        }
        collect(turn)
        assertEquals("多轮思考应由一个思考块统一管理", 1, reasoningSections)
        assertEquals("两张工具卡片应内联在同一主题内", 2, toolCards)

        val joined = allTexts.joinToString("\n")
        assertTrue("思考块应包含第一轮内容", joined.contains("第一轮思考"))
        assertTrue("思考块应包含第二轮内容", joined.contains("第二轮思考"))
        assertTrue("最终正文应完整可见", joined.contains("面板整体优化阶段"))
        assertTrue("回复主题不应为零尺寸：${turn.width}x${turn.height}", turn.width > 0 && turn.height > 0)
    }

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
        assertEquals("消息容器应挂载 2 个轮次气泡（用户 + 合并后的助手主题）", 2, bubbles.size)
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

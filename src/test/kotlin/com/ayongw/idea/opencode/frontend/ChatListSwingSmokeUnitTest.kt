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

    @Test
    fun 多工具卡片顺序为作者行思考块卡片正文且长输出多行预览() {
        val user = ChatMessage(
            id = "msg_11461dafd001qYidHotx361jLd", content = "当前项目的入口类在哪儿？",
            author = "me", isMyMessage = true
        )
        val pending = ChatMessage(
            id = "pending_thinking", content = "", author = "AI Buddy",
            type = ChatMessage.ChatMessageType.AI_THINKING
        )
        fun reasoning(id: String, text: String) = ChatMessage(
            id = "$id#reasoning", content = text, author = "AI Buddy",
            type = ChatMessage.ChatMessageType.AI_THINKING
        )
        fun tool(callId: String, input: String, output: String, status: ToolCallStatus) =
            ChatMessage(
                id = callId, content = "", author = "AI Buddy",
                type = ChatMessage.ChatMessageType.TOOL,
                tool = ToolCallDto(
                    callId = callId, name = "execute", input = input,
                    output = output, status = status,
                    exit = if (status == ToolCallStatus.COMPLETED) 0 else null,
                    truncated = false
                )
            )
        val inputJson = """{"code":"await tools[\"codegraph\"].codegraph_explore({ query: \"x\" })"}"""
        val output539 = buildString {
            append("**Exploration: Spring Boot 启动类 main 方法 Application**")
            repeat(538) { append("\n行").append(it + 2) }
        }
        val r1 = "msg_11461db2a0019UQ3nD9BBlGRAA"
        val r2 = "msg_11461eb64001"
        val r3 = "msg_11461fd4b001"

        // 按真实流式时序逐批推送（pending → 首轮思考 → 工具1运行/完成 → 二轮思考 →
        // 工具2运行/完成 → 三轮思考 → 正文）
        chatList.setMessages(listOf(user, pending))
        listOf(10, 21, 60, 81, 98, 116).forEach { len ->
            chatList.setMessages(listOf(user, reasoning(r1, "思考".repeat(len))))
        }
        chatList.setMessages(listOf(user, reasoning(r1, "思考x"), tool("functions.execute_1", inputJson, "", ToolCallStatus.RUNNING)))
        chatList.setMessages(listOf(user, reasoning(r1, "思考x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED)))
        listOf(9, 38).forEach { len ->
            chatList.setMessages(listOf(user, reasoning(r1, "x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED), reasoning(r2, "第二轮".repeat(len))))
        }
        chatList.setMessages(listOf(user, reasoning(r1, "x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED), reasoning(r2, "x"), tool("functions.execute_2", """{"query":"y"}""", "", ToolCallStatus.RUNNING)))
        chatList.setMessages(listOf(user, reasoning(r1, "x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED), reasoning(r2, "x"), tool("functions.execute_2", """{"query":"y"}""", "{", ToolCallStatus.COMPLETED)))
        listOf(61, 80, 115).forEach { len ->
            chatList.setMessages(listOf(user, reasoning(r1, "x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED), reasoning(r2, "x"), tool("functions.execute_2", """{"query":"y"}""", "{", ToolCallStatus.COMPLETED), reasoning(r3, "第三轮".repeat(len))))
        }
        val answerText = """
            入口类是 `Application`，位于：

            `user-service/src/main/java/com/switchpower/Application.java`

            ```java
            }
            ```

            是标准的 Spring Boot 启动类，APPID 已设为 `AppSwitchPowerUserService`。
        """.trimIndent()
        listOf(18, 119, 229, 385).forEach { len ->
            chatList.setMessages(listOf(user, reasoning(r1, "x"), tool("functions.execute_1", inputJson, output539, ToolCallStatus.COMPLETED), reasoning(r2, "x"), tool("functions.execute_2", """{"query":"y"}""", "{", ToolCallStatus.COMPLETED), reasoning(r3, "x"), ChatMessage(id = r3, content = answerText.take(len), author = "AI Buddy")))
        }

        // headless 下视口链路不分配尺寸：固定容器宽度后多趟布局，模拟换行收敛
        val messagesContainer = gridBagContainer(chatList)!!
        repeat(3) {
            messagesContainer.setSize(548, messagesContainer.preferredSize.height)
            layoutTree(messagesContainer)
        }
        val turn = messagesContainer.components.filterIsInstance<MessageBubble>().first { !it.isMy }

        // 1) 气泡内组件顺序：作者行 → 思考块 → 工具卡（按到达顺序）→ 正文
        val classes = turn.components.map { it::class.java.simpleName }
        assertTrue("首组件应为作者行，实际：$classes", classes.first() == "AuthorRow")
        val reasoningIndex = classes.indexOf("ReasoningSection")
        val cardIndices = classes.indices.filter { classes[it] == "ToolCallCard" }
        val contentIndex = classes.indexOf("JPanel")
        assertEquals("思考块应紧随作者行（index=2）", 2, reasoningIndex)
        assertEquals("应有两张工具卡且位于思考块之后", listOf(3, 4), cardIndices)
        assertTrue("正文应在所有工具卡之后，实际：$classes", contentIndex > cardIndices.last())

        // 2) 纵向位置同样满足 思考块 → 卡1 → 卡2 → 正文，且无重叠
        val cards = cardIndices.map { turn.components[it] }
        val reasoningY = turn.components[reasoningIndex].bounds.y
        assertTrue("思考块 y 应小于卡1", reasoningY < cards[0].bounds.y)
        assertTrue("卡1 应在卡2 之前", cards[0].bounds.y < cards[1].bounds.y)
        assertTrue("卡2 应在正文之前", cards[1].bounds.y < turn.components[contentIndex].bounds.y)
        assertTrue("卡1 与卡2 不应重叠", cards[0].bounds.y + cards[0].bounds.height <= cards[1].bounds.y)

        // 3) 卡1 长输出（539 行）默认按 12 行预览：滚动区高度 ≥ 12 行，而不是一行
        val card1Scroll = scrollPaneOf(cards[0] as Container)
        assertTrue(
            "539 行输出应展示 12 行预览（≥200px），实际高度=${card1Scroll.bounds.height}",
            card1Scroll.bounds.height >= 200
        )
        assertEquals(
            "滚动区实际高度应等于其 preferredSize 高度",
            card1Scroll.preferredSize.height, card1Scroll.bounds.height
        )

        // 4) 单行输出（卡2 的 "{"、正文内 JAVA 块的 "}"）保持单行高度，不被放大
        val card2Scroll = scrollPaneOf(cards[1] as Container)
        assertTrue(
            "单行输出应保持紧凑高度（28~60px），实际=${card2Scroll.bounds.height}",
            card2Scroll.bounds.height in 28..60
        )
        val content = turn.components[contentIndex] as Container
        val answerScroll = scrollPaneOf(content)
        assertTrue(
            "正文 JAVA 单行块应保持紧凑高度，实际=${answerScroll.bounds.height}",
            answerScroll.bounds.height in 28..60
        )

        // 5) 展开后不再截断行数：539 行全部渲染，块内滚动；收起恢复预览
        val pane = codeBlockPaneOf(cards[0] as Container)
        val toggleMethod = pane::class.java.getDeclaredMethod("toggle").apply { isAccessible = true }
        toggleMethod.invoke(pane)
        val area = pane::class.java.getDeclaredField("textArea").apply { isAccessible = true }
            .get(pane) as JTextArea
        assertEquals("展开后应渲染全部 539 行（不再截断为 500 行）", 539, area.text.lines().size)
        toggleMethod.invoke(pane)
        assertEquals("收起后恢复 12 行预览", 12, area.text.lines().size)
    }

    /** 取卡片/正文容器内的 CodeBlockPane（internal，测试模块不可见） */
    private fun codeBlockPaneOf(container: Container): Container =
        container.components.first { it::class.java.simpleName == "CodeBlockPane" } as Container

    /** 反射取卡片/正文容器内 CodeBlockPane 的 scrollPane（CodeBlockPane 为 internal，测试模块不可见） */
    private fun scrollPaneOf(container: Container): Component {
        val pane = codeBlockPaneOf(container)
        val field = pane::class.java.getDeclaredField("scrollPane").apply { isAccessible = true }
        return field.get(pane) as Component
    }

    /** 深度优先 doLayout（headless 下替代真实窗口校验，让每个容器拿到父级分配的尺寸） */
    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { child ->
            if (child is Container) layoutTree(child)
        }
    }
}

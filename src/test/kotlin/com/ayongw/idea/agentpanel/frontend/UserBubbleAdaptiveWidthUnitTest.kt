package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.ChatList
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.bubble.MessageBubble
import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Component
import javax.swing.JComponent
import java.awt.Container
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.Box

/**
 * 用户消息气泡的对齐契约。
 *
 * 最终形态：**布局满宽 + 背景铺满 + 框内正文右对齐**。
 *
 * 走过的弯路（勿回退）：
 * - 曾用 `fill = NONE` 做真自适应 → 开启换行的 JTextArea 无法知道目标宽度，preferred
 *   宽度恒为约 121px（实测 1 字符与 440 字符同为 121），长短消息气泡都固定 145px。
 * - 曾只把**背景**按文字宽度收窄贴右 → 正文 TextBlock 的 alignmentX 是 LEFT 且被
 *   BoxLayout 拉伸到满宽，文字实际渲染在左侧，于是「文字与背景框分离」。
 *
 * 要真正做到「紧凑且右对齐」必须让正文盒子本身变窄，需自定义 getPreferredSize 同时
 * 计算换行后高度，风险高于收益，故保持满宽。
 *
 */
class UserBubbleAdaptiveWidthUnitTest {

    private lateinit var project: com.intellij.openapi.project.Project
    private lateinit var chatList: ChatList

    private val panelWidth = 600

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("bubble-width", Files.createTempDirectory("bubble-width").toString())
        chatList = ChatList(project)
    }

    @After
    fun tearDown() {
        chatList.dispose()
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    @Test
    fun 布局满宽以保证按容器宽换行() {
        chatList.setMessages(listOf(user("u1", "这是一段很长的用户消息".repeat(40))))
        val bubble = layoutAndFindUserBubble()
        val container = messagesContainer()

        assertTrue(
            "气泡布局宽度应接近面板宽，实际=${bubble.width} 面板=${container.width}",
            bubble.width >= container.width - 40
        )
    }

    @Test
    fun 正文块首选高度按内容实测而非布局垃圾值() {
        // 回归：TextBlock 未显式给首选尺寸时，JTextArea(lineWrap=true) 的首选高度按
        // 「当前已分配宽度」算（首次布局宽度为 0），高度与内容完全无关 —— 一行文字也撑出
        // 三四行。现改为先 setSize(自然宽) 逼其换行再读回高度。
        //
        // 断言正文块本身而不是气泡：headless 下气泡高度依赖父级分配（实测为 0），
        // 而正文块的首选尺寸是确定性的。
        chatList.setMessages(listOf(user("u1", "当前时间")))
        val bubble = layoutAndFindUserBubble()
        val block = firstTextBlock(bubble)
        val probe = javax.swing.JLabel().apply { font = block.font }
        val lineHeight = probe.getFontMetrics(probe.font).height

        assertTrue(
            "单行正文的首选高度应约等于一行行高（≈$lineHeight），实际=${block.preferredSize}",
            block.preferredSize.height <= lineHeight * 2
        )
        assertTrue(
            "正文块宽度应等于文本自然宽度而非满宽，实际=${block.preferredSize}",
            block.preferredSize.width < panelWidth / 2
        )
    }

    @Test
    fun 短消息背景收窄且右边界与正文右边界重合() {
        // 这次改对了：正文宽度上限=自然宽度 → 正文真正贴右 → 背景可安全收窄到正文宽度。
        // 若再次出现「文字与背景框分离」，本断言与下一条（右对齐）会同时失败。
        chatList.setMessages(listOf(user("u1", "可以改")))
        val bubble = layoutAndFindUserBubble()
        val span = paintedSpan(bubble)
        val contentRight = collectContentWidth(bubble)

        assertTrue("短消息背景应收窄（实际宽=${span.count()}）", span.count() < panelWidth / 2)
        // 背景应比正文宽出一个 INNER_PADDING（气泡内边距），不多不少
        val padding = 10
        assertTrue(
            "背景右边界应= 正文右边界 + 内边距 $padding（背景右=${span.last} 正文右=$contentRight）",
            kotlin.math.abs(span.last - contentRight - padding) <= 4
        )
    }

    @Test
    fun 长消息背景仍铺满不窄于内容() {
        chatList.setMessages(listOf(user("u1", "这是一段很长的用户消息".repeat(40))))
        val bubble = layoutAndFindUserBubble()
        val span = paintedSpan(bubble)

        assertTrue(
            "长消息背景应接近满宽（实际宽=${span.count()} 气泡宽=${bubble.width}）",
            span.count() >= bubble.width - 40
        )
    }

    @Test
    fun 用户消息正文块右对齐而助手消息保持左对齐() {
        chatList.setMessages(listOf(user("u1", "用户消息"), assistant("a1", "助手消息")))
        val container = messagesContainer()
        repeat(3) {
            container.setSize(panelWidth, container.preferredSize.height)
            layoutTree(container)
        }
        val bubbles = container.components.filterIsInstance<MessageBubble>()

        assertEquals(
            "用户消息正文块应右对齐",
            Component.RIGHT_ALIGNMENT,
            alignmentXOf(firstTextBlock(bubbles.first { it.isMy }))
        )
        assertEquals(
            "助手消息正文块应保持左对齐",
            Component.LEFT_ALIGNMENT,
            alignmentXOf(firstTextBlock(bubbles.first { !it.isMy }))
        )
    }

    /** 返回背景着色像素的左右边界 */
    private fun paintedSpan(bubble: Container): IntRange {
        val img = java.awt.image.BufferedImage(
            bubble.width, bubble.height.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB
        )
        val g2d = img.createGraphics() as java.awt.Graphics2D
        bubble.paint(g2d)
        g2d.dispose()
        var left = -1
        var right = -1
        for (x in 0 until img.width) {
            if ((img.getRGB(x, img.height / 2) ushr 24) and 0xFF > 16) {
                if (left < 0) left = x
                right = x
            }
        }
        return left..right
    }

    /** 正文文本块是 internal，按类名定位（避免为测试放宽可见性） */
    private fun firstTextBlock(root: Container): JComponent {
        fun find(c: Container): JComponent? {
            for (child in c.components) {
                if (child is JComponent && child.javaClass.simpleName == "TextBlock") return child
                if (child is Container) find(child)?.let { return it }
            }
            return null
        }
        return find(root)!!
    }

    private fun alignmentXOf(component: JComponent): Float {
        val m = JComponent::class.java.getMethod("getAlignmentX")
        m.isAccessible = true
        return m.invoke(component) as Float
    }

    /** 正文块实际占宽的最大右边界（相对气泡） */
    private fun collectContentWidth(root: Container): Int {
        var max = 0
        for (child in root.components) {
            if (child.width > 0) max = maxOf(max, child.x + child.width)
            if (child is Container) max = maxOf(max, collectContentWidth(child))
        }
        return max
    }

    private fun user(id: String, text: String) = ChatMessage(
        id = id, content = text, author = "me", isMyMessage = true
    )

    private fun assistant(id: String, text: String) = ChatMessage(
        id = id, content = text, author = "AI Buddy"
    )

    private fun messagesContainer(): Container {
        fun find(root: Container): Container? {
            if (root.layout is GridBagLayout &&
                root.components.any { it is MessageBubble || it is Box.Filler }
            ) return root
            root.components.forEach { child ->
                if (child is Container) find(child)?.let { return it }
            }
            return null
        }
        return find(chatList)!!
    }

    private fun layoutAndFindUserBubble(): Container {
        val container = messagesContainer()
        repeat(3) {
            container.setSize(panelWidth, container.preferredSize.height)
            layoutTree(container)
        }
        return container.components.filterIsInstance<MessageBubble>().first { it.isMy }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { child -> if (child is Container) layoutTree(child) }
    }
}

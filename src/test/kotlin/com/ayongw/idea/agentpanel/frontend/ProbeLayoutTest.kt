package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.ChatList
import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.ayongw.idea.agentpanel.shared.ToolCallDto
import com.ayongw.idea.agentpanel.shared.ToolCallStatus
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ProbeLayoutTest {

    private lateinit var project: Project
    private lateinit var chatList: ChatList

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("probe", Files.createTempDirectory("probe").toString())
        chatList = ChatList(project)
    }

    @After
    fun tearDown() {
        chatList.dispose()
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun gridBagContainer(root: Container): Container? {
        if (root.layout is GridBagLayout && root.components.any { it::class.java.simpleName == "MessageBubble" }) return root
        root.components.forEach { child ->
            if (child is Container) gridBagContainer(child)?.let { return it }
        }
        return null
    }

    private fun dump(c: Component, depth: Int = 0) {
        val pad = "  ".repeat(depth)
        val extra = when (c) {
            is JScrollPane -> " viewPos=${c.viewport.viewPosition} extent=${c.viewport.extentSize} view=${c.viewport.viewSize} hbar=${c.horizontalScrollBar.isVisible} vbar=${c.verticalScrollBar.isVisible}"
            is JTextArea -> " lines=${c.text.lines().size} visibleRect=${c.visibleRect}"
            else -> ""
        }
        println("$pad${c.javaClass.simpleName} b=${c.bounds} pref=${c.preferredSize}$extra")
        (c as? Container)?.components?.forEach { dump(it, depth + 1) }
    }

    private fun run(lines: Int, longLines: Boolean, panelWidth: Int) {
        println("======== lines=$lines long=$longLines panelW=$panelWidth")
        val body = (1..lines).joinToString("\n") { i ->
            if (longLines) "%3d| %s".format(i, "abcdefghij ".repeat(6).trim()) else "line $i"
        }
        val msg = ChatMessage(
            id = "call_1", content = "", author = "AI Buddy", type = ChatMessage.ChatMessageType.TOOL,
            tool = ToolCallDto(
                callId = "call_1", name = "read", input = """{"path":"/x/y.md"}""",
                output = body, status = ToolCallStatus.COMPLETED
            )
        )
        val user = ChatMessage(id = "u1", content = "读一下文件", author = "me", isMyMessage = true)
        SwingUtilities.invokeAndWait {
            chatList.setSize(panelWidth, 800)
            layoutTree(chatList)
            chatList.setMessages(listOf(user, msg))
        }
        Thread.sleep(40)
        SwingUtilities.invokeAndWait {
            chatList.setMessages(listOf(user, msg))
            val c = gridBagContainer(chatList)!!
            repeat(3) {
                c.setSize(c.width, c.preferredSize.height)
                layoutTree(c)
            }
            dump(c)
        }
    }

    @Test
    fun probe() {
        run(12, false, 600)
        run(12, true, 600)
        run(15, true, 600)
        run(16, true, 600)
        run(40, true, 600)
        run(158, true, 600)
        run(158, false, 600)
        run(158, true, 470)
        System.out.flush()
    }
}

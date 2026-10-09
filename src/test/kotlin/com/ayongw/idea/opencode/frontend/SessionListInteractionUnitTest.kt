package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.SessionItem
import com.ayongw.idea.opencode.frontend.chatApp.ui.SessionList
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import com.intellij.ui.components.JBList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Container
import java.time.LocalDateTime
import java.awt.event.MouseEvent
import java.nio.file.Files

/**
 * 「全部会话」列表的交互契约：
 * - 单击即切换（此前要求双击，用户表现为「点了没反应」）
 * - 行尾删除槽命中 → 删除，且**不**触发切换
 * - 删除策略统一：只剩最后一个会话时也允许删除（删除后由上层自动新建兜底）
 */
class SessionListInteractionUnitTest {

    private lateinit var project: Project
    private lateinit var sessionList: SessionList

    private val clicked = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private val listWidth = 280
    private val rowHeight = 56

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("session-list", Files.createTempDirectory("session-list").toString())
        sessionList = SessionList(
            project = project,
            onSessionClick = { clicked += it },
            onNewSession = {},
            onRenameSession = { _, _ -> },
            onDeleteSession = { deleted += it }
        )
        sessionList.setSize(listWidth, rowHeight * SESSIONS.size)
        sessionList.updateSessions(sessions(), CURRENT_ID)
        layoutTree(sessionList)
    }

    @After
    fun tearDown() {
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    private data class Fixture(val sessionId: String, val title: String, val updatedAt: java.time.LocalDateTime)

    private companion object {
        const val CURRENT_ID = "ses_1"
        val SESSIONS = listOf(
            Fixture("ses_1", "第一个会话", LocalDateTime.of(2026, 10, 7, 11, 20)),
            Fixture("ses_2", "第二个会话", LocalDateTime.of(2026, 10, 6, 15, 37))
        )

        /** 点击行内非删除槽区域（左侧 1/3 处） */
        private const val PLAIN_X = 80
    }

    private fun sessions() = SESSIONS.map {
        com.ayongw.idea.opencode.shared.SessionStateDto(
            sessionId = it.sessionId,
            title = it.title,
            status = com.ayongw.idea.opencode.shared.SessionStatus.IDLE,
            pendingPermission = null,
            createdAt = it.updatedAt,
            updatedAt = it.updatedAt,
            contextFiles = emptyList()
        )
    }

    /** 深度优先找内部 JBList（SessionList 未对外暴露该组件） */
    private fun jbList(): JBList<SessionItem> {
        fun walk(c: Container): JBList<SessionItem>? {
            if (c is JBList<*>) @Suppress("UNCHECKED_CAST") return c as JBList<SessionItem>
            c.components.forEach { child ->
                if (child is Container) walk(child)?.let { return it }
            }
            return null
        }
        return walk(sessionList) ?: error("未找到会话列表 JBList")
    }

    private fun click(list: JBList<SessionItem>, x: Int, y: Int, button: Int = MouseEvent.BUTTON1) {
        list.dispatchEvent(
            MouseEvent(
                list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                x, y, 1, button == MouseEvent.BUTTON3, button
            )
        )
    }

    private fun move(list: JBList<SessionItem>, x: Int, y: Int) {
        list.dispatchEvent(MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false, 0))
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { child -> if (child is Container) layoutTree(child) }
    }

    @Test
    fun 单击即切换会话() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        click(list, PLAIN_X, 10)

        assertEquals("单击应触发切换", listOf("ses_1"), clicked)
        assertTrue("单击不应触发删除", deleted.isEmpty())
    }

    @Test
    fun 点击第二行切换对应会话() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        click(list, PLAIN_X, rowHeight + 10)

        assertEquals("应切换到第二行会话", listOf("ses_2"), clicked)
    }

    @Test
    fun 点击行尾删除槽触发删除且不切换() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        click(list, listWidth - 4, 10)

        assertEquals("应删除该行会话", listOf("ses_1"), deleted)
        assertTrue("删除槽命中不应同时触发切换", clicked.isEmpty())
    }

    @Test
    fun 删除槽之外的右侧区域仍是切换() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        // 距右边 60px：已在删除槽（22px）之外，属普通区域
        click(list, listWidth - 60, 10)

        assertEquals("删除槽外应切换", listOf("ses_1"), clicked)
        assertTrue("不应误删", deleted.isEmpty())
    }

    @Test
    fun 悬停后删除槽依然可命中() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        move(list, PLAIN_X, 10)
        click(list, listWidth - 4, 10)

        assertEquals("hover 后删除槽仍应可命中", listOf("ses_1"), deleted)
    }

    @Test
    fun 只剩一个会话时仍可删除() {
        // 删除策略统一：允许删到空，由 SessionController.removeTabAndRelocate 自动新建兜底
        sessionList.updateSessions(sessions().take(1), CURRENT_ID)
        val list = jbList()
        list.setSize(listWidth, rowHeight)
        layoutTree(list)

        click(list, listWidth - 4, 10)

        assertEquals("最后一个会话也应可删除", listOf("ses_1"), deleted)
    }

    @Test
    fun 右键不切换会话() {
        val list = jbList()
        list.setSize(listWidth, rowHeight * 2)
        layoutTree(list)

        // 右键会走到 showContextMenu → JBPopupMenu.show，headless 下组件未 showing 会抛
        // IllegalComponentStateException；此处容忍该环境限制，只断言「未切换、未删除」。
        runCatching { click(list, PLAIN_X, 10, MouseEvent.BUTTON3) }

        assertTrue("右键应只弹菜单，不切换", clicked.isEmpty())
        assertTrue("右键不应删除", deleted.isEmpty())
    }
}
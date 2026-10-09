package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.SessionItem
import com.ayongw.idea.opencode.frontend.chatApp.ui.SessionList
import com.ayongw.idea.opencode.frontend.chatApp.ui.SessionRow
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionStatus
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
import java.nio.file.Files
import java.time.LocalDateTime
import javax.swing.DefaultListModel

/**
 * 「全部会话」弹窗的 Active / History 分组与过滤。
 *
 * 分组的意义：已打开（tab 中存在）的会话混在历史里时，用户点进去才发现"这个我刚开过"。
 * 过滤的意义：会话多起来后靠滚动找不动。
 */
class SessionListGroupingUnitTest {

    private lateinit var project: Project
    private lateinit var sessionList: SessionList

    private val clicked = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("grouping", Files.createTempDirectory("grouping").toString())
        sessionList = SessionList(
            project = project,
            onSessionClick = { clicked += it },
            onNewSession = {},
            onRenameSession = { _, _ -> },
            onDeleteSession = { deleted += it }
        )
    }

    @After
    fun tearDown() {
        runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    private data class Fx(val id: String, val title: String, val hour: Int)

    private companion object {
        val FX = listOf(
            Fx("ses_1", "Git 提交信息外壳变更排查", 11),
            Fx("ses_2", "BIZ-10541 单测覆盖完成", 17),
            Fx("ses_3", "重跑分支单测账本", 16),
            Fx("ses_4", "关闭待生效权益", 14)
        )
    }

    private fun sessions(): List<SessionStateDto> = FX.map {
        SessionStateDto(
            sessionId = it.id,
            title = it.title,
            status = SessionStatus.IDLE,
            pendingPermission = null,
            createdAt = LocalDateTime.of(2026, 10, 8, it.hour, 0),
            updatedAt = LocalDateTime.of(2026, 10, 8, it.hour, 0),
            contextFiles = emptyList()
        )
    }

    private fun rows(): List<SessionRow> {
        val list = jbList()
        val model = list.model as DefaultListModel<SessionRow>
        return (0 until model.size()).map { model.getElementAt(it) }
    }

    private fun headers(): List<String> = rows().filterIsInstance<SessionRow.Header>().map { it.title }

    private fun itemIds(): List<String> =
        rows().filterIsInstance<SessionRow.Item>().map { it.session.sessionId }

    private fun jbList(): JBList<SessionRow> {
        fun walk(c: Container): JBList<SessionRow>? {
            if (c is JBList<*>) {
                @Suppress("UNCHECKED_CAST")
                return c as JBList<SessionRow>
            }
            c.components.forEach { child ->
                if (child is Container) walk(child)?.let { return it }
            }
            return null
        }
        return walk(sessionList) ?: error("未找到会话列表")
    }

    private fun setFilter(text: String) {
        val field = findFilterField()
        field.text = text
    }

    private fun findFilterField(): javax.swing.JTextField {
        fun walk(c: Container): javax.swing.JTextField? {
            if (c is javax.swing.JTextField) return c
            c.components.forEach { child ->
                if (child is Container) walk(child)?.let { return it }
            }
            return null
        }
        return walk(sessionList) ?: error("未找到过滤输入框")
    }

    @Test
    fun 已打开会话排在未打开之前且各有标题() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1", "ses_2"))

        assertEquals("应有两个分组标题", 2, headers().size)
        assertTrue("第一个分组应为 Active", headers()[0].contains("Active", ignoreCase = true))
        assertTrue("第二个分组应为 History", headers()[1].contains("History", ignoreCase = true))
        // 组内按时间倒序：Active{ses_2(17时), ses_1(11时)} + History{ses_3(16时), ses_4(14时)}
        assertEquals(
            "已打开的应在未打开之前，各组内按时间倒序",
            listOf("ses_2", "ses_1", "ses_3", "ses_4"),
            itemIds()
        )
    }

    @Test
    fun 分组内按更新时间倒序() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1", "ses_2"))

        val ids = itemIds()
        assertTrue("Active 组内按时间倒序（17时 在 11时 之前）", ids.indexOf("ses_2") < ids.indexOf("ses_1"))
        assertTrue("History 组内按时间倒序（16时 在 14时 之前）", ids.indexOf("ses_3") < ids.indexOf("ses_4"))
    }

    @Test
    fun 全部已打开时不输出空的History标题() {
        sessionList.updateSessions(sessions(), "ses_1", FX.map { it.id })

        assertEquals("只有一组时不应有第二个标题", 1, headers().size)
        assertEquals(4, itemIds().size)
    }

    @Test
    fun 全部未打开时不输出空的Active标题() {
        sessionList.updateSessions(sessions(), "ses_1", emptyList())

        assertEquals("只有一组时不应有第一个标题", 1, headers().size)
        assertTrue(headers()[0].contains("History", ignoreCase = true))
    }

    @Test
    fun 过滤按标题匹配且大小写不敏感() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1"))
        setFilter("biz")

        assertEquals("只应匹配 BIZ-10541 那条", listOf("ses_2"), itemIds())
    }

    @Test
    fun 过滤对两个分组同时生效() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1", "ses_2"))
        setFilter("单测")

        // ses_2(Active) 与 ses_3(History) 都含"单测" → 两组都保留
        assertEquals(2, headers().size)
        assertEquals(listOf("ses_2", "ses_3"), itemIds())
    }

    @Test
    fun 过滤后某组为空则不输出该组标题() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1"))
        setFilter("关闭待生效")

        assertEquals("只剩一组，不应有 Active 标题", 1, headers().size)
        assertEquals(listOf("ses_4"), itemIds())
    }

    @Test
    fun 过滤无匹配时列表为空() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1"))
        setFilter("不存在的会话标题")

        assertTrue("无匹配时不应有任何行", rows().isEmpty())
    }

    @Test
    fun 清空过滤恢复全部分组() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1"))
        setFilter("BIZ")
        assertEquals(1, itemIds().size)

        setFilter("")
        assertEquals("清空过滤应恢复全部", 4, itemIds().size)
        assertEquals(2, headers().size)
    }

    @Test
    fun 分组标题行不可点击不切换() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1"))
        val list = jbList()
        list.setSize(400, 400)
        list.doLayout()

        val headerIndex = rows().indexOfFirst { it is SessionRow.Header }
        val bounds = list.getCellBounds(headerIndex, headerIndex)!!
        // 在标题行上单击：不触发切换、不触发删除
        list.dispatchEvent(
            java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 60, bounds.y + bounds.height / 2, 1, false, java.awt.event.MouseEvent.BUTTON1
            )
        )

        assertTrue("标题行不应触发切换", clicked.isEmpty())
        assertTrue("标题行不应触发删除", deleted.isEmpty())
    }

    @Test
    fun 标题与时间顺序不受过滤影响() {
        sessionList.updateSessions(sessions(), "ses_1", listOf("ses_1", "ses_2"))
        val firstItem = rows().first { it is SessionRow.Item }
        val item = SessionItem((firstItem as SessionRow.Item).session)

        assertTrue("标题应来自会话", item.title.isNotBlank())
        assertTrue("时间应形如 MM-dd HH:mm", item.timestamp.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}""")))
    }
}
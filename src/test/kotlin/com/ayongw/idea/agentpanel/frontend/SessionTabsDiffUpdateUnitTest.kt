package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.SessionTabComponent
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.SessionTabs
import com.ayongw.idea.agentpanel.shared.SessionStateDto
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.time.LocalDateTime

/**
 * 会话 tab 栏的差量更新契约。
 *
 * 核心保证：**切换会话时 tab 组件实例被复用、不重建**。
 * `allSessionsFlow` 每次切换都会发射，旧实现 `removeAll()` 重建会让用户按下到释放之间
 * 组件被抽离容器，点击被吞（实机症状"要 点好几次"）。
 */
class SessionTabsDiffUpdateUnitTest {

    private lateinit var tabs: SessionTabs
    private val selected = mutableListOf<String>()

    @Before
    fun setUp() {
        tabs = SessionTabs(
            onSelect = { selected += it },
            onClose = {},
            onNewSession = {},
            onShowAllSessions = {},
            onOpenSettings = {}
        )
    }

    private fun dto(id: String, title: String) = SessionStateDto(
        sessionId = id,
        title = title,
        status = com.ayongw.idea.agentpanel.shared.SessionStatus.IDLE,
        pendingPermission = null,
        createdAt = LocalDateTime.of(2026, 10, 7, 11, 20),
        updatedAt = LocalDateTime.of(2026, 10, 7, 11, 20),
        contextFiles = emptyList()
    )

    private fun tabComponents(): List<SessionTabComponent> {
        fun walk(c: Container): List<SessionTabComponent> {
            val self = if (c is SessionTabComponent) listOf(c) else emptyList()
            return self + c.components.filterIsInstance<Container>().flatMap { walk(it) }
        }
        return walk(tabs)
    }

    @Test
    fun 切换选中态时组件实例复用() {
        val sessions = listOf(dto("ses_1", "A"), dto("ses_2", "B"))

        tabs.update(sessions, listOf("ses_1", "ses_2"), "ses_1")
        val before = tabComponents()
        assertEquals("应挂载 2 个 tab", 2, before.size)

        // 只改选中态（切换会话的常态路径）
        tabs.update(sessions, listOf("ses_1", "ses_2"), "ses_2")
        val after = tabComponents()

        assertEquals("组件数量不变", 2, after.size)
        assertSame("选中态变化不得重建组件", before[0], after[0])
        assertSame("选中态变化不得重建组件", before[1], after[1])
    }

    @Test
    fun 重复刷新同一状态不重建() {
        val sessions = listOf(dto("ses_1", "A"), dto("ses_2", "B"))
        tabs.update(sessions, listOf("ses_1", "ses_2"), "ses_1")
        val before = tabComponents()

        repeat(5) { tabs.update(sessions, listOf("ses_1", "ses_2"), "ses_1") }
        val after = tabComponents()

        after.forEachIndexed { i, c -> assertSame("第 $i 个 tab 应被复用", before[i], c) }
    }

    @Test
    fun 关闭tab只移除对应实例() {
        val sessions = listOf(dto("ses_1", "A"), dto("ses_2", "B"), dto("ses_3", "C"))
        tabs.update(sessions, listOf("ses_1", "ses_2", "ses_3"), "ses_1")
        val before = tabComponents()
        val ses2 = before.single { it.fullTitle == "B" }

        tabs.update(sessions, listOf("ses_1", "ses_3"), "ses_1")
        val after = tabComponents()

        assertEquals("关闭后只剩 2 个", 2, after.size)
        assertTrue("被关闭的 B 不应残留", after.none { it.fullTitle == "B" })
        assertSame("未关闭的 tab 仍复用", before.single { it.fullTitle == "A" }, after.first())
        // 被移除的实例与保留的实例不是同一个
        assertNotEquals(ses2.fullTitle, after.first().fullTitle)
    }

    @Test
    fun 新开tab只新增不重建既有() {
        val s1 = listOf(dto("ses_1", "A"))
        tabs.update(s1, listOf("ses_1"), "ses_1")
        val first = tabComponents().single()

        tabs.update(s1 + listOf(dto("ses_2", "B")), listOf("ses_1", "ses_2"), "ses_1")
        val after = tabComponents()

        assertEquals("应新增到 2 个", 2, after.size)
        assertSame("既有 tab 应复用", first, after.first())
    }

    @Test
    fun 顺序变化时按序重挂且复用实例() {
        val sessions = listOf(dto("ses_1", "A"), dto("ses_2", "B"))
        tabs.update(sessions, listOf("ses_1", "ses_2"), "ses_1")
        val before = tabComponents()

        tabs.update(sessions, listOf("ses_2", "ses_1"), "ses_2")
        val after = tabComponents()

        assertEquals("按新顺序显示 B 在前", listOf("B", "A"), after.map { it.fullTitle })
        assertSame("重挂应复用实例（不重建）", before[1], after[0])
        assertSame("重挂应复用实例（不重建）", before[0], after[1])
    }

    @Test
    fun 标题变更就地刷新不重建() {
        tabs.update(listOf(dto("ses_1", "旧标题")), listOf("ses_1"), "ses_1")
        val before = tabComponents()
        tabs.update(listOf(dto("ses_1", "新标题")), listOf("ses_1"), "ses_1")
        val after = tabComponents().single()

        assertSame("仅改标题不应重建", before.single(), after)
        assertEquals("标题应就地更新", "新标题", after.fullTitle)
    }

    @Test
    fun 空态与有tab之间可来回切换() {
        tabs.update(emptyList(), emptyList(), null)
        assertTrue("无已打开会话时应显示空态", emptyLabelPresent())

        tabs.update(listOf(dto("ses_1", "A")), listOf("ses_1"), "ses_1")
        assertTrue("有 tab 时空态应移除", !emptyLabelPresent())
        assertEquals("应挂载 1 个 tab", 1, tabComponents().size)

        tabs.update(emptyList(), emptyList(), null)
        assertTrue("再次清空应回到空态", emptyLabelPresent())
        assertTrue("清空后不应残留 tab", tabComponents().isEmpty())
    }

    @Test
    fun 重复调用update不产生重复空态标签() {
        repeat(3) { tabs.update(emptyList(), emptyList(), null) }
        assertEquals("空态标签不应重复挂载", 1, emptyLabelCount())
    }

    @Test
    fun 相邻状态反复切换不产生重复tab() {
        val sessions = listOf(dto("ses_1", "A"))
        repeat(3) {
            tabs.update(sessions, listOf("ses_1"), "ses_1")
            tabs.update(emptyList(), emptyList(), null)
        }
        tabs.update(sessions, listOf("ses_1"), "ses_1")
        assertEquals("反复切换后仍只有 1 个 tab", 1, tabComponents().size)
    }

    @Test
    fun 点击tab触发选中回调() {
        tabs.update(listOf(dto("ses_1", "A")), listOf("ses_1"), null)
        tabComponents().single().dispatchEvent(
            java.awt.event.MouseEvent(
                tabComponents().single(),
                java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 5, 5, 1, false,
                java.awt.event.MouseEvent.BUTTON1
            )
        )
        assertEquals("点击 tab 应回调切换", listOf("ses_1"), selected)
    }

    @Test
    fun 新旧实例类型一致便于断言() {
        tabs.update(listOf(dto("ses_1", "A")), listOf("ses_1"), "ses_1")
        val before = tabComponents().single()
        assertNotEquals(null, before.fullTitle)
        tabs.update(listOf(dto("ses_1", "B")), listOf("ses_1"), "ses_1")
        assertSame(before, tabComponents().single())
    }

    private fun allComponents(): List<Component> {
        fun walk(c: Container): List<Component> = c.components.toList() + c.components.filterIsInstance<Container>().flatMap { walk(it) }
        return walk(tabs)
    }

    /** 空态提示标签：结构上是唯一「不在 tab 内、宽度不受 tab 上限约束」的 JBLabel */
    private fun emptyLabels(): List<JBLabel> = allComponents().filterIsInstance<JBLabel>()
        .filterNot { label ->
            generateSequence(label.parent) { it.parent }.any { it is SessionTabComponent }
        }

    private fun emptyLabelPresent(): Boolean = emptyLabels().isNotEmpty()

    private fun emptyLabelCount(): Int = emptyLabels().size
}
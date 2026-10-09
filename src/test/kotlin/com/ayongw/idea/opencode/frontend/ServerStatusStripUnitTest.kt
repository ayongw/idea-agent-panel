package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ServerStatusStrip
import com.ayongw.idea.opencode.shared.ServerStateDto
import com.intellij.testFramework.TestApplicationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.util.Locale
import java.util.ResourceBundle
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPasswordField

/**
 * Server 状态条的显隐与按钮可用性（TSD-31 §5.1 / §10.3）
 *
 * 只断言「哪些状态显示整条、显示哪些按钮、凭据输入是否可用」，不校验像素与配色；
 * 文案从 bundle 读取，避免把英文文案硬编进断言。
 */
class ServerStatusStripUnitTest {

    private lateinit var strip: ServerStatusStrip

    private var retried = false
    private var startedOwn = false
    private var submitted: Pair<String, String>? = null
    private var settingsOpened = false
    private var docsOpened = false

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
        retried = false
        startedOwn = false
        submitted = null
        settingsOpened = false
        docsOpened = false
        strip = ServerStatusStrip(
            onRetry = { retried = true },
            onStartOwnInstance = { startedOwn = true },
            onSubmitCredentials = { username, password -> submitted = username to password },
            onOpenSettings = { settingsOpened = true },
            onOpenCliDocs = { docsOpened = true },
        )
    }

    @Test
    fun `就绪与空闲状态整条隐藏`() {
        listOf(
            ServerStateDto.STATE_IDLE,
            ServerStateDto.STATE_READY,
            ServerStateDto.STATE_REUSING,
            ServerStateDto.STATE_STOPPED,
        ).forEach { state ->
            strip.update(ServerStateDto(state = state))
            assertFalse("$state 不应占用纵向空间", strip.isVisible)
        }
    }

    @Test
    fun `启动中显示进度且不提供重试`() {
        listOf(ServerStateDto.STATE_DISCOVERING, ServerStateDto.STATE_STARTING).forEach { state ->
            strip.update(ServerStateDto(state = state, port = 4096))
            assertTrue("$state 应显示状态条", strip.isVisible)
            assertTrue("$state 不应提供重试", !effectivelyVisible(button(RETRY)))
        }
    }

    @Test
    fun `失败时提供重试与设置入口`() {
        strip.update(
            ServerStateDto(
                state = ServerStateDto.STATE_FAILED,
                failure = ServerStateDto.FAILURE_UNREACHABLE,
                detail = "连接被拒",
            )
        )

        assertTrue(strip.isVisible)
        assertTrue("失败态应可重试", effectivelyVisible(button(RETRY)))
        assertTrue("失败态应可跳设置", effectivelyVisible(button(SETTINGS)))
        assertFalse("非 CLI 缺失不显示安装引导", effectivelyVisible(button(INSTALL_GUIDE)))

        assertTrue("点击重试应回调", click(button(RETRY)) && retried)
        assertTrue("点击设置应回调", click(button(SETTINGS)) && settingsOpened)
    }

    @Test
    fun `CLI 缺失时额外提供安装引导`() {
        strip.update(
            ServerStateDto(
                state = ServerStateDto.STATE_FAILED,
                failure = ServerStateDto.FAILURE_CLI_NOT_FOUND,
                outputTail = listOf("exec: opencode: not found"),
            )
        )

        assertTrue("CLI 缺失应给安装引导", effectivelyVisible(button(INSTALL_GUIDE)))
        assertTrue("有输出尾巴时应可展开", effectivelyVisible(button(SHOW_OUTPUT)))
        assertTrue("点击引导应回调", click(button(INSTALL_GUIDE)) && docsOpened)
    }

    @Test
    fun `需要凭据时就地输入并可提交`() {
        strip.update(
            ServerStateDto(
                state = ServerStateDto.STATE_NEEDS_CREDENTIALS,
                port = 4097,
                detail = "该 Server 不是本插件启动",
            )
        )

        assertTrue(strip.isVisible)
        assertTrue("应提供改用自启实例", effectivelyVisible(button(START_OWN)))
        assertTrue("应提供设置入口改地址", effectivelyVisible(button(SETTINGS)))

        val password = descendants(strip, JPasswordField::class.java).firstOrNull()
        assertNotNull("应提供密钥输入框", password)
        assertTrue("密钥输入框应可用", effectivelyVisible(password!!))

        assertTrue("点击自启应回调", click(button(START_OWN)) && startedOwn)
    }

    @Test
    fun `窄宽度下按钮独占一行且不与文案重叠`() {
        strip.update(
            ServerStateDto(
                state = ServerStateDto.STATE_FAILED,
                failure = ServerStateDto.FAILURE_CLI_NOT_FOUND,
                detail = "未找到 opencode CLI",
            )
        )
        // 830px 是实测会发生重叠的工具窗宽度，取更窄的 520px 兜住下限
        strip.setSize(520, 400)
        layoutTree(strip)

        val label = descendants(strip, JLabel::class.java)
            .first { it.text == bundle.getString("server.failure.cli.not.found") }
        val labelRow = rowOf(label)
        val buttonRow = rowOf(button(RETRY))

        assertNotSame("按钮必须与文案分行（同行时 BorderLayout 会挤压成重叠）", labelRow, buttonRow)
        assertTrue(
            "按钮行应整体位于文案行下方，不得重叠",
            buttonRow.bounds.y >= labelRow.bounds.y + labelRow.bounds.height,
        )
    }

    @Test
    fun `无操作按钮时按钮行折叠不占空间`() {
        strip.update(ServerStateDto(state = ServerStateDto.STATE_STARTING, port = 4096))
        assertFalse("启动中无任何按钮，按钮行不应占纵向空间", rowOf(button(RETRY)).isVisible)
    }

    // ==================== 夹具 ====================

    /** 组件所在的直接子行（父级即状态条本身的行容器） */
    private fun rowOf(component: Component): Container = component.parent as Container

    /** 深度优先强制布局（headless 下组件未挂到 Window 上，不走 validate 链路） */
    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { child ->
            if (child is Container) layoutTree(child)
        }
    }

    /** 沿父链判断可见性（组件未挂到 Window 上时 `isShowing` 恒为 false） */
    private fun effectivelyVisible(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    private fun click(component: Component): Boolean {
        val button = component as? JButton ?: return false
        if (!button.isEnabled) return false
        button.doClick()
        return true
    }

    private fun button(text: String): Component {
        val found = descendants(strip, JButton::class.java).firstOrNull { it.text == text }
        assertNotNull("未找到文案为「$text」的按钮", found)
        return found!!
    }

    private fun <T : Component> descendants(root: Container, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        root.components.forEach { child ->
            if (type.isInstance(child)) result += type.cast(child)
            if (child is Container) result += descendants(child, type)
        }
        return result
    }

    private companion object {
        /** 与 `OpencodeFrontendBundle` 同一份 bundle，避免把英文文案硬编进断言 */
        val bundle: ResourceBundle = ResourceBundle.getBundle("messages.OpencodeFrontendBundle", Locale.ENGLISH)

        val RETRY = bundle.getString("server.strip.action.retry")
        val SETTINGS = bundle.getString("server.strip.action.settings")
        val INSTALL_GUIDE = bundle.getString("server.strip.action.cli.docs")
        val START_OWN = bundle.getString("server.strip.action.start.own")
        val SHOW_OUTPUT = bundle.getString("server.strip.action.show.output")
    }
}
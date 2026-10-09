package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具调用卡片：头部行（工具名 + 状态 + 退出码）恒可见，入参与输出可折叠。
 *
 * 折叠策略（对齐 Kiro 历史消息）：
 * - **执行中**（[ToolCallStatus.STREAMING] / [RUNNING]）自动展开，边跑边看
 * - **到达终态**（[COMPLETED] / [ERROR]）默认折叠输出，只留头部行
 * - 用户手动点过头部行后，以用户选择为准（[userOverrides]），不会被后续状态刷新覆盖——
 *   卡片每次状态变化都是整体重建，没有持久化字段可依赖
 *
 * 入参用 [ToolInputBlock]（按宽度换行），输出用 [CodeBlockPane]（不换行 + 横向滚动）。
 */
class ToolCallCard(private val tool: ToolCallDto) : JPanel() {

    private val outputPresent = tool.output.isNotBlank()
    private val terminal = tool.status == ToolCallStatus.COMPLETED || tool.status == ToolCallStatus.ERROR

    /** 折叠态：展开时不显示输出 */
    private val expanded: Boolean = userOverrides[tool.callId] ?: !terminal

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        add(buildHeader())

        // 入参：统一用「头部行 → 内容」的 SMAll 间距，与是否折叠输出无关（问题 2）
        tool.input.takeIf { it.isNotBlank() }?.let { input ->
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(ToolInputBlock(input))
        }

        if (outputPresent) {
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            if (expanded) {
                add(CodeBlockPane(tool.name.ifBlank { FALLBACK_NAME }, tool.output))
            } else {
                // 折叠时给一行摘要占位，头部行可点击展开
                add(buildCollapsedHint())
            }
        }
    }

    private fun buildHeader() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))

        add(JBLabel(tool.name.ifBlank { FALLBACK_NAME }).apply {
            font = JBFont.small().asBold()
            foreground = ChatAppColors.Text.normal
        })
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(JBLabel(statusLabel()).apply {
            font = JBFont.small()
            foreground = statusColor()
        })
        tool.exit?.let { code ->
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(OpencodeFrontendBundle.message("chat.tool.exit") + " " + code).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        if (tool.truncated) {
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(OpencodeFrontendBundle.message("chat.tool.truncated")).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        add(Box.createHorizontalGlue())

        if (outputPresent) {
            toggleIcon()
        }
    }

    /** 折叠摘要行（与展开态的头部行同高，双击/点击均可切换） */
    private fun buildCollapsedHint() = JBLabel(
        OpencodeFrontendBundle.message("chat.tool.output.collapsed", previewOfOutput())
    ).apply {
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
        alignmentX = LEFT_ALIGNMENT
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = EmptyBorder(0, 0, 0, 0)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = toggleOutput()
        })
    }

    private fun JPanel.toggleIcon() {
        val icon = JBLabel().apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = OpencodeFrontendBundle.message(
                if (expanded) "chat.tool.output.hide" else "chat.tool.output.show"
            )
            icon = if (expanded) {
                com.intellij.icons.AllIcons.Actions.Expandall
            } else {
                com.intellij.icons.AllIcons.Actions.Collapseall
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) = toggleOutput()
            })
        }
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
        add(icon)
    }

    /**
     * 切换输出折叠并重建整张卡片。
     *
     * 直接 `setVisible` 换内容做不到（输出块是 CodeBlockPane，高度受行数驱动），
     * 复用 MessageBubble 现有的「移除旧卡 + 同位置插入新卡」重建路径最省事。
     */
    private fun toggleOutput() {
        userOverrides[tool.callId] = !expanded
        if (!isShowing) {
            // 卡片尚未挂载（构造期被点击不可能发生），兜底直接刷新
            return
        }
        val parent = parent
        val index = parent?.components?.indexOf(this) ?: -1
        if (parent == null || index < 0) return
        parent.remove(this)
        parent.add(ToolCallCard(tool), index)
        parent.revalidate()
        parent.repaint()
    }

    /** 折叠摘要用的输出首行（截断到 [COLLAPSED_PREVIEW_CHARS]） */
    private fun previewOfOutput(): String {
        val firstLine = tool.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (firstLine.length <= COLLAPSED_PREVIEW_CHARS) {
            firstLine
        } else {
            firstLine.take(COLLAPSED_PREVIEW_CHARS) + "…"
        }
    }

    private fun statusLabel(): String = when (tool.status) {
        ToolCallStatus.STREAMING -> OpencodeFrontendBundle.message("chat.tool.status.streaming")
        ToolCallStatus.RUNNING -> OpencodeFrontendBundle.message("chat.tool.status.running")
        ToolCallStatus.COMPLETED -> OpencodeFrontendBundle.message("chat.tool.status.completed")
        ToolCallStatus.ERROR -> OpencodeFrontendBundle.message("chat.tool.status.failed")
    }

    private fun statusColor(): Color = when (tool.status) {
        ToolCallStatus.COMPLETED -> ChatAppColors.Tool.success
        ToolCallStatus.ERROR -> ChatAppColors.Tool.error
        else -> ChatAppColors.Tool.running
    }

    companion object {
        const val FALLBACK_NAME = "tool"
        private const val HEADER_HEIGHT = 20
        private const val COLLAPSED_PREVIEW_CHARS = 60

        /**
         * 用户对输出折叠态的手动选择（`callId` → 是否展开）。
         *
         * 必须跨卡片重建保留：工具每次状态变化都会 `ToolCallCard(tool)` 整体重建，
         * 若只存在实例字段里，用户手动展开后下一个状态包（output 追加）就会把它重新折叠。
         */
        private val userOverrides = ConcurrentHashMap<String, Boolean>()

        /** 会话切换 / 消息列表重建时清空，避免 callId 复用后残留旧折叠态 */
        fun resetOverrides() = userOverrides.clear()

        /** 折叠默认策略：终态折叠、执行中展开。抽成函数便于单测直接断言 */
        fun defaultExpanded(status: ToolCallStatus): Boolean =
            status != ToolCallStatus.COMPLETED && status != ToolCallStatus.ERROR
    }
}
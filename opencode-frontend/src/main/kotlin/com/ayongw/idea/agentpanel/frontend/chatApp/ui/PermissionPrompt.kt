package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.agentpanel.shared.PendingPermissionDto
import com.ayongw.idea.agentpanel.shared.PermissionResponse
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 权限确认条：事件流 `permission.asked` 到达时显示在输入框上方。
 *
 * 三个按钮对应 `once / always / reject`（与后端 `PermissionDecision` 枚举一致）；
 * 无待决项时整块隐藏，点击后立即收起（不等服务端事件），避免重复回复。
 */
class PermissionPrompt(
    private val onDecide: (requestId: String, response: PermissionResponse) -> Unit
) : JPanel() {

    private val messageLabel = JBLabel()

    private var requestId: String? = null

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(JBUI.scale(ChatUIConstants.Spacing.SMALL))

        messageLabel.apply {
            font = JBFont.small()
            foreground = ChatAppColors.Status.warning
        }

        add(messageLabel)
        add(Box.createHorizontalGlue())
        add(createDecideButton(AgentPanelBundle.message("chat.permission.reject")) { PermissionResponse.REJECT })
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(createDecideButton(AgentPanelBundle.message("chat.permission.always")) { PermissionResponse.ALLOW_ALWAYS })
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(createDecideButton(AgentPanelBundle.message("chat.permission.once")) { PermissionResponse.ALLOW_ONCE })

        isVisible = false
    }

    /** 更新待决权限；null 表示收起 */
    fun update(permission: PendingPermissionDto?) {
        requestId = permission?.requestId
        if (permission == null) {
            isVisible = false
            return
        }

        val title = AgentPanelBundle.message("chat.permission.title")
        val resources = permission.resources.joinToString(RESOURCE_SEPARATOR)
        messageLabel.text = if (resources.isBlank()) {
            "$title${permission.action}"
        } else {
            "$title${permission.action}$SEPARATOR$resources"
        }
        isVisible = true
        revalidate()
        repaint()
    }

    private fun createDecideButton(label: String, response: () -> PermissionResponse) =
        ButtonUtils.ToolbarButton(label).apply {
            font = JBFont.small()
            foreground = ChatAppColors.Text.normal
            border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(8))
            toolTipText = label
            addActionListener {
                val id = requestId ?: return@addActionListener
                update(null)
                onDecide(id, response())
            }
        }

    private companion object {
        const val SEPARATOR = " · "
        const val RESOURCE_SEPARATOR = ", "
    }
}
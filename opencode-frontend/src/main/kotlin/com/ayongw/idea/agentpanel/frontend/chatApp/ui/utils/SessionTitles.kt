package com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle

/**
 * 会话标题展示
 *
 * opencode 新建会话可能没有标题（`title` 为空），展示时统一兜底为「New Session」，
 * 不写回服务端，避免把占位文案变成真实标题。
 */
object SessionTitles {

    fun display(title: String?): String =
        title?.takeIf { it.isNotBlank() } ?: AgentPanelBundle.message("chat.session.untitled")
}
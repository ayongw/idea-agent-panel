package com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Centralized icons used by the Chat sample UI.
 * Grouped by feature area to keep call-sites tidy and consistent.
 */
object ChatAppIcons {
    object Header {
        val search: Icon = AllIcons.Actions.Find
        val close: Icon = AllIcons.Actions.Cancel
    }

    object TopBar {
        val newSession: Icon = AllIcons.General.Add
        val allSessions: Icon = AllIcons.Vcs.History
        val settings: Icon = AllIcons.General.Settings
        val closeTab: Icon = AllIcons.Actions.Close
    }

    object Search {
        val previous: Icon = AllIcons.Actions.PreviousOccurence
        val next: Icon = AllIcons.Actions.NextOccurence
    }

    object Prompt {
        val send: Icon = AllIcons.RunConfigurations.TestState.Run
        val stop: Icon = AllIcons.Run.Stop

        /** 附件按钮（＋）：选择本机文件 / 目录作为上下文 */
        val attach: Icon = AllIcons.General.Add
    }

    object Context {
        val remove: Icon = AllIcons.Actions.Cancel
    }

    object Session {
        val rename: Icon = AllIcons.Actions.EditSource
        val delete: Icon = AllIcons.General.Remove
        val duplicate: Icon = AllIcons.Actions.Copy
    }
}
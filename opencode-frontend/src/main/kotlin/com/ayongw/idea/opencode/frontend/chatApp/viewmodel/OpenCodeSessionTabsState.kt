package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 项目级持久化的会话 tab 状态
 *
 * 关闭 IDE 再打开时恢复「已打开的会话 tab」与「当前会话」，避免每次都要重新从历史里找。
 * 会话本身仍以 opencode 为唯一真源，这里只存 id 与顺序。
 *
 * 注册方式与 [com.ayongw.idea.opencode.frontend.settings.OpenCodeSettingsState] 一致：
 * 走模块 xml 的 `projectService`（持久化组件用显式注册，不依赖注解注册）。
 */
@State(
    name = "OpenCodeSessionTabs",
    storages = [Storage("opencode-session-tabs.xml")]
)
class OpenCodeSessionTabsState : PersistentStateComponent<OpenCodeSessionTabsState> {

    /** 已打开的会话 tab（按打开顺序） */
    var openedSessionIds: MutableList<String> = mutableListOf()

    /** 当前选中的会话 */
    var currentSessionId: String? = null

    override fun getState(): OpenCodeSessionTabsState = this

    override fun loadState(state: OpenCodeSessionTabsState) {
        openedSessionIds = state.openedSessionIds.filter { it.isNotBlank() }.toMutableList()
        currentSessionId = state.currentSessionId?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeSessionTabsState =
            project.getService(OpenCodeSessionTabsState::class.java)
    }
}
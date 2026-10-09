package com.ayongw.idea.agentpanel.frontend.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.ayongw.idea.agentpanel.frontend.CoroutineScopeHolder
import com.ayongw.idea.agentpanel.frontend.chatApp.AgentChatApp
import com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel.ChatViewModel
import com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel.FrontendChatRepositoryModel
import com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel.AgentSessionTabsState

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        /** 工具窗 id（plugin.xml 注册同名 EP；自动显示活动也用它查找） */
        const val TOOL_WINDOW_ID = "Idea Agent Panel"
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        openCodeChatApp(project, toolWindow)
    }

    private fun openCodeChatApp(project: Project, toolWindow: ToolWindow) {
        val viewModel = ChatViewModel(
            CoroutineScopeHolder.getInstance(project).createScope(ChatViewModel::class.java.simpleName),
            FrontendChatRepositoryModel.getInstance(project),
            project.basePath,
            AgentSessionTabsState.getInstance(project)
        )
        Disposer.register(toolWindow.disposable, viewModel)

        val chatPanel = AgentChatApp(viewModel, project)
        // 面板级生命周期：随工具窗销毁，订阅/子组件 scope 一并取消（TSD-30 §5.4）
        Disposer.register(toolWindow.disposable, chatPanel)
        // displayName 传 null：标题由工具窗自身提供（id=OpenCode），否则新 UI 会多渲染一个同名 content tab
        val content = ContentFactory.getInstance().createContent(chatPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
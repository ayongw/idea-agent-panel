package com.ayongw.idea.opencode.frontend.toolWindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目启动后自动显示一次 OpenCode 工具窗。
 *
 * 新 UI 对「从未激活过」的工具窗不常驻显示侧边条按钮，导致新项目里找不到插件入口；
 * 启动时 show() 一次让按钮进入项目布局常驻（副作用：每次打开项目面板会自动展开）。
 */
internal class OpenCodeToolWindowAutoShowActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            ToolWindowManager.getInstance(project)
                .getToolWindow(OpenCodeToolWindowFactory.TOOL_WINDOW_ID)
                ?.show()
        }
    }
}

package com.ayongw.idea.agentpanel.frontend.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开后在后台协程预加载 Server 密码：
 * 设置页的 `reload` / `isModified` 运行在 EDT，只能读内存缓存；
 * 预加载确保打开设置页时密码已就绪，不会因 EDT 上访问 PasswordSafe
 * （`SlowOperations` 禁令）而读空，进而导致 Test Connection 用到旧密码。
 *
 * 多项目重复打开时 [OpenCodePasswordStore.preload] 幂等。
 */
internal class OpenCodePasswordPreloadActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        runCatching { OpenCodePasswordStore.preload() }
    }
}

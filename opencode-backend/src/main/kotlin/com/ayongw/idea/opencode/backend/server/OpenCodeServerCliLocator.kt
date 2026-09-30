package com.ayongw.idea.opencode.backend.server

import java.io.File

/**
 * `opencode` CLI 路径解析（TSD-31 §3.6）
 *
 * 顺序：设置项 `cliPath`（非空且可执行）→ 在 `PATH` 中查找 `opencode`（Windows 额外尝试 `.cmd` / `.exe`）。
 * 解析失败即失败分类 `CLI_NOT_FOUND`，由调用方触发引导安装（见 §5.3）。
 *
 * 纯逻辑：`PATH` 与「是否可执行」的判定都可注入，便于单测覆盖各平台与命中/未命中矩阵。
 */
class OpenCodeServerCliLocator(
    private val pathEnv: String? = System.getenv("PATH"),
    private val isExecutable: (String) -> Boolean = { candidate ->
        File(candidate).let { it.isFile && it.canExecute() }
    },
    osName: String = System.getProperty("os.name").orEmpty(),
) {

    private val windows = osName.startsWith("Windows", ignoreCase = true)

    /** 返回可执行文件绝对路径；未找到返回 null */
    fun locate(configuredPath: String? = null): String? {
        configuredPath?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
            return configured.takeIf { isExecutable(it) }
        }
        val names = if (windows) WINDOWS_NAMES else UNIX_NAMES
        return pathEnv.orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.asSequence().map { name -> File(dir, name).absolutePath } }
            .firstOrNull { isExecutable(it) }
    }

    private companion object {
        val UNIX_NAMES = listOf("opencode")
        val WINDOWS_NAMES = listOf("opencode.cmd", "opencode.exe", "opencode")
    }
}
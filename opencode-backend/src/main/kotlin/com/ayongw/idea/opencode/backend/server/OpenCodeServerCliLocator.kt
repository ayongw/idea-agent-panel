package com.ayongw.idea.opencode.backend.server

import java.io.File

/**
 * `opencode` CLI 路径解析（TSD-31 §3.6）
 *
 * 顺序：设置项 `cliPath`（非空且可执行）→ 在 `PATH` 中查找 `opencode`（Windows 额外尝试 `.cmd` / `.exe`）。
 * 解析失败即失败分类 `CLI_NOT_FOUND`，由调用方触发引导安装（见 §5.3）。
 *
 * `PATH` 由调用方以惰性 supplier 注入：从 Dock / Finder 启动的 IDE 其进程 `PATH` 极窄
 * （`/usr/bin:/bin:/usr/sbin:/sbin`），而 opencode 官方安装脚本落在 `~/.opencode/bin`、
 * npm 全局安装落在 nvm 的 bin 目录，都只出现在登录 shell 的 `PATH` 里。生产装配传入
 * [com.ayongw.idea.opencode.backend.mcp.LoginShellPath.effectivePath]（登录 shell 优先 +
 * 进程 `PATH` 兜底）；惰性取值避免构造期就阻塞在 shell 探测上。
 *
 * 纯逻辑：`PATH` 与「是否可执行」的判定都可注入，便于单测覆盖各平台与命中/未命中矩阵。
 */
class OpenCodeServerCliLocator(
    private val pathEnv: () -> String? = { System.getenv("PATH") },
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
        return pathEnv().orEmpty()
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
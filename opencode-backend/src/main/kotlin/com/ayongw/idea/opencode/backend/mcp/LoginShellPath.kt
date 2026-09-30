package com.ayongw.idea.opencode.backend.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用户登录 shell 的 `PATH` 探测
 *
 * 从 Finder / Dock 启动的 IDE，其进程 `PATH` 只有 `/usr/bin:/bin:/usr/sbin:/sbin`，
 * 而 MCP 配置里的命令常是裸名（`codegraph`、`npx`），实际装在 nvm / homebrew / `~/.local/bin` 下，
 * 直接用进程 `PATH` 起进程会报 `Cannot run program "codegraph" ... error: 2 (No such file or directory)`。
 * 这里按终端口径取一次登录 shell 的 PATH（登录 + 交互，与用户手动执行为准），同一会话内缓存
 * （成败都缓存，避免每次展开都等一次 shell 启动）。
 *
 * 取不到（无 `SHELL`、超时、异常）返回 null，调用方退回进程自身 `PATH`。
 */
class LoginShellPath(
    private val shell: String? = System.getenv("SHELL"),
    private val probe: (String) -> String? = ::probeShellPath
) {

    @Volatile
    private var probed = false
    private var result: String? = null

    /** 登录 shell 的 PATH；无法探测时为 null */
    fun resolve(): String? {
        val shellPath = shell?.trim().orEmpty()
        if (shellPath.isEmpty()) return null
        if (probed) return result
        synchronized(this) {
            if (probed) return result
            result = runCatching { normalize(probe(shellPath)) }.getOrNull()
            probed = true
            return result
        }
    }

    /**
     * 给子进程用的 `PATH`：登录 shell 的条目在前（与终端一致），[fallback]（通常是进程自身 PATH）兜底，
     * 按序去重；两者都为空时返回 null（保持继承原样）。
     */
    fun effectivePath(fallback: String? = System.getenv("PATH")): String? =
        listOfNotNull(resolve(), fallback)
            .flatMap { it.split(File.pathSeparator) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)
            .takeIf { it.isNotEmpty() }

    /** 交互 shell 可能先打印横幅，取最后一行像路径的输出（PATH 的每一段都是目录，必含 `/`） */
    private fun normalize(output: String?): String? = output
        ?.lines()
        ?.map { it.trim() }
        ?.lastOrNull { it.contains('/') }
        ?.takeIf { it.isNotBlank() }
}

/** 探测超时：rc 卡住时不能让 MCP 拉取一直等 */
private const val PROBE_TIMEOUT_MS = 3000L

/** 等待输出读完的宽限时间（进程已退出，通常立即读完） */
private const val DRAIN_JOIN_MS = 500L

private fun probeShellPath(shell: String): String? {
    val process = ProcessBuilder(shell, "-lic", "printf %s \"\$PATH\"")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    try {
        val output = StringBuilder()
        val drain = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }
        drain.isDaemon = true
        drain.start()
        if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        drain.join(DRAIN_JOIN_MS)
        return output.toString().trim().takeIf { it.isNotEmpty() }
    } finally {
        process.destroyForcibly()
    }
}
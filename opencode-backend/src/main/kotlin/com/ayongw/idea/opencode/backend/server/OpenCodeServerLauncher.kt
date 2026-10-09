package com.ayongw.idea.opencode.backend.server

import com.ayongw.idea.opencode.backend.mcp.LoginShellPath
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 拉起自有实例所需的参数 */
data class OpenCodeServerLaunchSpec(
    val port: Int,
    /** 工作目录（当前 Project 的 basePath），保证 server 默认会话归属与工作区一致 */
    val workingDirectory: Path? = null,
    /** 自有实例密码：只经环境变量传递，绝不进 argv（TSD-31 §3.6） */
    val password: String? = null,
    /** 设置项覆盖的 CLI 路径 */
    val cliPath: String? = null,
)

/** CLI 不存在或不可执行（对应失败分类 `CLI_NOT_FOUND`，触发引导安装） */
class OpenCodeServerCliNotFoundException(message: String) : RuntimeException(message)

/** 已拉起的自有进程句柄（进程管理的唯一门面，Manager 只通过它判定存活/终止/读输出） */
interface OpenCodeServerProcess {
    /** 子进程 pid；取不到时为 -1 */
    val pid: Long

    /** 该进程的输出缓冲（逐行脱敏，有界） */
    val outputBuffer: OpenCodeServerOutputBuffer

    val isAlive: Boolean

    /** 等待进程退出；返回是否已在超时内退出 */
    fun awaitExit(timeoutMs: Long): Boolean

    /** 优雅终止：`destroyProcess()` → 宽限 [graceMs] → `killProcess()`；返回后进程已终止或已被强杀 */
    fun terminate(graceMs: Long = DEFAULT_GRACE_MS)

    /** 注册退出回调（供 Manager 做自愈判断）；进程已退出时立即回调 */
    fun addTerminationListener(listener: () -> Unit)

    companion object {
        /** 优雅终止宽限期（§3.7 建议 3s） */
        const val DEFAULT_GRACE_MS = 3_000L
    }
}

/**
 * 进程拉起与终止（TSD-31 §3.6 / §3.7）
 *
 * - 命令：默认 `opencode serve --port <port>`，可用 [commandFactory] 注入（集成测试用桩进程）；
 * - 进程对象：[KillableProcessHandler] + `setShouldDestroyProcessRecursively(true)`，使 `destroyProcess()` 带走进程树；
 * - **不**调用 `killProcessTree()`：它在 `OSProcessHandler` 上是 `protected`，外部不可调用（§12 A3）；
 * - 输出：`ProcessListener.onTextAvailable` → [OpenCodeServerOutputBuffer]（写入即脱敏）。
 */
open class OpenCodeServerLauncher(
    /**
     * 登录 shell 的 `PATH` 探测：IDE 进程 `PATH` 极窄（从 Dock 启动时只有系统目录），
     * opencode 装在 `~/.opencode/bin` 或 nvm 的 bin 下时用它补齐（同 MCP 侧见 [LoginShellPath]）。
     */
    private val loginShellPath: LoginShellPath = LoginShellPath(),
    private val cliLocator: OpenCodeServerCliLocator = OpenCodeServerCliLocator(
        pathEnv = { loginShellPath.effectivePath() },
    ),
    private val outputBuffer: OpenCodeServerOutputBuffer = OpenCodeServerOutputBuffer(),
    private val commandFactory: (OpenCodeServerLaunchSpec, String) -> List<String> = ::defaultCommand,
) {

    private val log = Logger.getInstance(OpenCodeServerLauncher::class.java)

    /**
     * 解析 CLI 可执行路径（设置页只读展示用），与 [launch] 走同一份定位逻辑，未找到返回 null。
     *
     * 阻塞：默认装配下首次调用要探一次登录 shell 的 `PATH`（结果有缓存），调用方放后台线程。
     */
    fun resolveCliPath(configuredPath: String? = null): String? = cliLocator.locate(configuredPath)

    /**
     * 拉起子进程并开始采集输出
     *
     * @throws OpenCodeServerCliNotFoundException CLI 未找到或不可执行（含可执行文件不存在导致的启动失败）
     */
    open fun launch(spec: OpenCodeServerLaunchSpec): OpenCodeServerProcess {
        val cliPath = cliLocator.locate(spec.cliPath)
            ?: throw OpenCodeServerCliNotFoundException(
                "未找到 opencode CLI（cliPath=${spec.cliPath?.takeIf { it.isNotBlank() } ?: "<未配置>"}）",
            )

        val commandLine = GeneralCommandLine(commandFactory(spec, cliPath)).withCharset(Charsets.UTF_8)
        spec.workingDirectory?.let { commandLine.withWorkingDirectory(it) }
        spec.password?.takeIf { it.isNotBlank() }?.let { password ->
            outputBuffer.registerSecret(password)
            commandLine.withEnvironment(ENV_SERVER_PASSWORD, password)
        }

        val handler = try {
            KillableProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            throw OpenCodeServerCliNotFoundException("启动 opencode 失败：${e.message}")
        }
        handler.setShouldDestroyProcessRecursively(true)

        val handle = OpenCodeServerProcessHandle(handler, outputBuffer)
        handler.addProcessListener(handle)
        handler.startNotify()

        log.info("OpenCode server 子进程已拉起：pid=${handle.pid} port=${spec.port}")
        return handle
    }

    companion object {
        /** `OPENCODE_SERVER_PASSWORD` 为 opencode 官方识别的环境变量（§12 A11/A15） */
        const val ENV_SERVER_PASSWORD = "OPENCODE_SERVER_PASSWORD"

        private const val PID_TIMEOUT_MS = 2_000L

        fun defaultCommand(spec: OpenCodeServerLaunchSpec, cliPath: String): List<String> =
            listOf(cliPath, "serve", "--port", spec.port.toString())
    }
}

/** [KillableProcessHandler] 上的进程句柄实现：唯一持有平台进程对象的地方 */
internal class OpenCodeServerProcessHandle(
    private val handler: KillableProcessHandler,
    override val outputBuffer: OpenCodeServerOutputBuffer,
) : OpenCodeServerProcess, ProcessListener {

    private val terminated = CountDownLatch(1)
    private val terminationListeners = CopyOnWriteArrayList<() -> Unit>()

    private val pidValue: Long by lazy {
        runCatching { handler.nativePid?.get(PID_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull() ?: -1L
    }

    override val pid: Long get() = pidValue

    override val isAlive: Boolean get() = !handler.isProcessTerminated

    override fun awaitExit(timeoutMs: Long): Boolean =
        terminated.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

    override fun terminate(graceMs: Long) {
        if (handler.isProcessTerminated) return
        handler.destroyProcess()
        if (awaitExit(graceMs)) return
        if (handler.canKillProcess()) {
            handler.killProcess()
        }
        awaitExit(FORCE_KILL_TIMEOUT_MS)
    }

    override fun addTerminationListener(listener: () -> Unit) {
        terminationListeners += listener
        if (handler.isProcessTerminated) listener()
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        outputBuffer.append(event.text)
    }

    override fun processTerminated(event: ProcessEvent) {
        outputBuffer.appendLine("process exited with code ${event.exitCode}")
        terminated.countDown()
        terminationListeners.forEach { runCatching { it() } }
    }

    private companion object {
        const val FORCE_KILL_TIMEOUT_MS = 5_000L
        const val PID_TIMEOUT_MS = 2_000L
    }
}
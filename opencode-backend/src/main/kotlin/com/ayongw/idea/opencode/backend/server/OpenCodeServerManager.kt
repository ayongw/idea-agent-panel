package com.ayongw.idea.opencode.backend.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

/** 当前连接配置（由设置页下发；密码已按「显式值 → 环境变量 → service.json」解析） */
data class OpenCodeServerConnectionConfig(
    val serverUrl: String,
    val username: String,
    val password: String?,
    /** 设置项覆盖的 CLI 路径 */
    val cliPath: String? = null,
    /** 是否允许插件自动拉起 server（默认开） */
    val autoStartServer: Boolean = true,
    /** 是否允许复用非本插件启动的实例（含经密钥接入） */
    val reuseExternalServer: Boolean = true,
)

/** 管理器对外界的全部交互点：T6 由 Project 侧实现，单测用假实现 */
interface OpenCodeServerHost {
    /** 读取当前连接配置 */
    fun connectionConfig(): OpenCodeServerConnectionConfig

    /** 工作目录（`Project.basePath`） */
    fun workingDirectory(): Path?

    /** 端点就绪后下发给连接层（沿用既有 `updateServerConfig`，仅端点变化时调用一次） */
    fun onEndpointReady(endpoint: OpenCodeServerEndpoint)

    /** 插件版本（写入注册表，供归属排查） */
    fun pluginVersion(): String
}

/** 管理器的可注入协作者（生产用默认实现，单测全量替换） */
class OpenCodeServerDeps(
    val discovery: OpenCodeServerDiscovery = OpenCodeServerDiscovery(),
    val launcher: OpenCodeServerLauncher = OpenCodeServerLauncher(),
    val registry: OpenCodeServerRegistry,
    val resolver: OpenCodeServerEndpointResolver = OpenCodeServerEndpointResolver(),
    val portAllocator: OpenCodePortAllocator = OpenCodePortAllocator(),
    /** 参考标识：`"<ideProcessId>:<projectHash>"`，用于共享注册表的引用计数 */
    val referenceId: () -> String = { defaultReferenceId() },
    /** 异步执行（重启退避等）；单测注入直通实现 */
    val executor: (() -> Unit) -> Unit = { task -> Thread(task, "openCodeServer-manager").start() },
    /** 退避等待（可注入假实现，避免单测真等待） */
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** pid 存活探测（默认真实探测；单测注入假实现） */
    val isProcessAlive: (Long) -> Boolean = ::defaultIsProcessAlive,
    /** 按 pid 终止进程（最后一个引用者可能不是拉起方，只能按 pid 终止）；返回是否成功发出终止信号 */
    val killByPid: (Long) -> Boolean = ::defaultKillByPid,
) {
    companion object {
        fun defaultReferenceId(): String =
            "${ProcessHandle.current().pid()}:${System.getProperty("user.dir").orEmpty().hashCode()}"

        fun defaultIsProcessAlive(pid: Long): Boolean =
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        /** 先 `destroy()`（SIGTERM 语义），仍未退出再 `destroyForcibly()` */
        fun defaultKillByPid(pid: Long): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            runCatching { handle.destroy() }
            if (handle.isAlive) runCatching { handle.destroyForcibly() }
            return true
        }
    }
}

/** 对外可见的运行状态（UI 状态条 / RPC 只读它） */
data class OpenCodeServerStatus(
    val state: OpenCodeServerState = OpenCodeServerState.IDLE,
    /** 当前生效端点（READY/REUSING 时非空） */
    val endpoint: OpenCodeServerEndpoint? = null,
    val failure: OpenCodeServerFailure? = null,
    val detail: String? = null,
    val port: Int? = null,
    /** 端点是否由本插件拉起（决定能否终止） */
    val owned: Boolean = false,
    /** 共享注册表上的引用者数量（自有实例才有意义） */
    val refCount: Int = 0,
    /** 失败时的输出尾巴（已脱敏） */
    val outputTail: List<String> = emptyList(),
) {
    val isReady: Boolean get() = state == OpenCodeServerState.READY || state == OpenCodeServerState.REUSING
}

/**
 * Server 生命周期编排与自愈（TSD-31 §3.2 / §3.7 / §3.8）
 *
 * **唯一 owner**：只有本类知道「Server 从哪来、是否由我拉起、引用计数何时归零、何时重启」。
 *
 * 编排是**同步阻塞**的（由调用方放在后台线程执行），这样状态机可脱离协程确定性单测；
 * 自愈重启经 [OpenCodeServerDeps.executor] 异步触发，退避用 [OpenCodeServerDeps.sleep]。
 *
 * 关键约束：
 * - **绝不终止非自己拉起的进程**（`REUSING` 的他人实例只断开连接）；
 * - 自有实例生命周期由共享注册表的**引用计数**决定：`refCount > 0` 时只递减，归零才优雅终止；
 * - 自愈上限 3 次 / 10 分钟窗口，超限转 `FAILED`（原因 `PROCESS_EXITED`）等用户重试。
 */
class OpenCodeServerManager(
    private val host: OpenCodeServerHost,
    private val deps: OpenCodeServerDeps,
) : Disposable {

    private val log = Logger.getInstance(OpenCodeServerManager::class.java)

    private val _status = MutableStateFlow(OpenCodeServerStatus())
    val status: StateFlow<OpenCodeServerStatus> = _status.asStateFlow()

    /** 由本插件拉起的进程句柄；仅当它非空时才允许终止 */
    @Volatile
    private var process: OpenCodeServerProcess? = null

    /** 已就绪且为本插件所有的端口（决定能否终止 / 参与引用计数） */
    @Volatile
    private var ownedPort: Int? = null

    /** 上次下发给连接层的地址（端点变化判定） */
    @Volatile
    private var lastPublishedUrl: String? = null

    /** 自愈额度（时间戳窗口） */
    private val restartTimestamps = ArrayDeque<Long>()

    /** 健康检查连续失败次数 */
    @Volatile
    private var healthFailureStreak: Int = 0

    @Volatile
    private var disposed: Boolean = false

    /** 插件启动（项目打开）时调用：探测 → 复用 / 引导 / 启动 */
    fun ensureStarted() {
        if (disposed) return
        transition(OpenCodeServerState.DISCOVERING)
        try {
            discover()
        } catch (e: Exception) {
            log.info("Server 发现失败", e)
            fail(OpenCodeServerFailure.UNREACHABLE, "发现过程异常：${e.message}")
        }
    }

    /** 用户点「重试」 */
    fun retry() = ensureStarted()

    /**
     * 用户显式停止自有 Server（等价于强制归零引用后终止）
     *
     * `REUSING` 的他人实例**不做任何事**（绝不能杀用户的进程）。
     */
    fun stopOwnedServer(): Boolean {
        val handle = process ?: return false
        val port = ownedPort ?: return false
        transition(OpenCodeServerState.STOPPING, port = port)
        // 用户显式停止 = 强制归零：先删除注册表条目，再终止进程（此后退出回调不会触发自愈）
        deps.registry.remove(port)
        process = null
        ownedPort = null
        handle.terminate()
        transition(OpenCodeServerState.STOPPED, port = port)
        return true
    }

    /**
     * 提交他人实例的密钥（`NEEDS_CREDENTIALS` 交互）
     *
     * 验证通过则复用（`REUSING`），否则保持提示。
     */
    fun submitCredentials(username: String?, password: String?): Boolean {
        val endpoint = _status.value.endpoint ?: return false
        val candidate = endpoint.copy(
            username = username?.trim()?.takeIf { it.isNotBlank() } ?: endpoint.username,
            password = password?.trim()?.takeIf { it.isNotBlank() } ?: endpoint.password,
        )
        return when (val result = deps.discovery.probe(candidate)) {
            is OpenCodeServerProbeResult.Ready -> {
                reuseExternal(candidate)
                true
            }

            is OpenCodeServerProbeResult.NotReady -> {
                _status.value = _status.value.copy(detail = "凭据校验未通过：${result.detail}")
                false
            }
        }
    }

    /** 健康检查（单次）：连续失败达阈值视为死亡，走同一自愈路径 */
    fun checkHealth(): Boolean {
        val endpoint = _status.value.endpoint ?: return false
        if (!_status.value.isReady) return false
        val healthy = deps.discovery.probe(endpoint) is OpenCodeServerProbeResult.Ready
        healthFailureStreak = if (healthy) 0 else healthFailureStreak + 1
        if (!healthy && healthFailureStreak >= HEALTH_FAILURE_THRESHOLD) {
            healthFailureStreak = 0
            onServerDead("健康检查连续失败 $HEALTH_FAILURE_THRESHOLD 次")
        }
        return healthy
    }

    /** 排障兜底：清空共享注册表（不停进程） */
    fun resetRegistry() = deps.registry.clear()

    override fun dispose() {
        disposed = true
        healthFailureStreak = 0
        val port = ownedPort
        // 释放一个引用：只有「最后一个引用者」才真正终止进程（多工作区/多窗口共享同一 server）
        if (port != null) {
            when (val outcome = deps.registry.release(port, deps.referenceId())) {
                is OpenCodeServerReleaseOutcome.LastReleaser -> {
                    log.info("最后一个引用者释放，终止自有 server：port=$port pid=${outcome.entry.pid}")
                    terminateProcess(outcome.entry.pid)
                }

                is OpenCodeServerReleaseOutcome.StillReferenced -> {
                    log.info("仍有引用者，保留自有 server：port=$port refCount=${outcome.entry.refCount}")
                }

                OpenCodeServerReleaseOutcome.NotRegistered -> Unit
            }
        }
        process = null
        ownedPort = null
        _status.value = OpenCodeServerStatus(state = OpenCodeServerState.STOPPED)
    }

    // ==================== 发现与复用 ====================

    /** 终止自有进程：优先用本地句柄（优雅终止 + 强杀），否则按 pid 终止（最后一个引用者可能不是拉起方） */
    private fun terminateProcess(pid: Long) {
        val handle = process?.takeIf { it.pid == pid }
        if (handle != null) {
            handle.terminate()
            return
        }
        deps.killByPid(pid)
    }

    private fun discover() {
        val config = host.connectionConfig()
        val candidates = deps.resolver.discoveryCandidates(config.serverUrl, config.username, config.password)

        // 探测前清理陈旧条目与崩溃残留引用（持锁，见 §3.7）
        runCatching { deps.registry.cleanupStale(deps.isProcessAlive) }
            .onSuccess { report ->
                if (report.removedEntries.isNotEmpty() || report.removedStaleReferences > 0) {
                    log.info(
                        "注册表清理：移除条目=${report.removedEntries} 过期引用=${report.removedStaleReferences} " +
                            "崩溃残留端口=${report.orphanedPorts}",
                    )
                }
            }

        candidates.forEach { candidate ->
            when (val result = deps.discovery.probe(candidate)) {
                is OpenCodeServerProbeResult.Ready -> {
                    val registered = deps.registry.findByPort(candidate.port)?.takeIf { it.pid == result.info.pid }
                    if (registered != null) {
                        reuseOwned(candidate.copy(owned = true))
                    } else if (config.reuseExternalServer) {
                        reuseExternal(candidate)
                    } else {
                        return@forEach
                    }
                    return
                }

                is OpenCodeServerProbeResult.NotReady -> {
                    if (result.failure == OpenCodeServerFailure.AUTH_FAILED) {
                        if (deps.registry.findByPort(candidate.port) != null) {
                            // 自有实例认证失败：与事件流口径一致，立即停止重试
                            fail(OpenCodeServerFailure.AUTH_FAILED, "自有实例认证失败：${result.detail}", candidate)
                        } else if (config.reuseExternalServer) {
                            transition(
                                OpenCodeServerState.NEEDS_CREDENTIALS,
                                endpoint = candidate,
                                detail = "该 Server 不是本插件启动，需要密钥才能接入",
                            )
                        } else {
                            fail(OpenCodeServerFailure.AUTH_FAILED, result.detail, candidate)
                        }
                        return
                    }
                }
            }
        }

        if (!config.autoStartServer) {
            fail(OpenCodeServerFailure.UNREACHABLE, "未找到可用的 OpenCode Server，且已关闭自动启动")
            return
        }
        startOwnServer(config)
    }

    private fun reuseOwned(endpoint: OpenCodeServerEndpoint) {
        val referenceId = deps.referenceId()
        val entry = deps.registry.acquire(endpoint.port, referenceId)
        ownedPort = endpoint.port
        publish(
            status = OpenCodeServerStatus(
                state = OpenCodeServerState.REUSING,
                endpoint = endpoint,
                port = endpoint.port,
                owned = true,
                refCount = entry?.refCount ?: 1,
            ),
            state = OpenCodeServerState.REUSING,
        )
    }

    private fun reuseExternal(endpoint: OpenCodeServerEndpoint) {
        process = null
        ownedPort = null
        publish(
            status = OpenCodeServerStatus(
                state = OpenCodeServerState.REUSING,
                endpoint = endpoint.copy(owned = false),
                port = endpoint.port,
                owned = false,
            ),
            state = OpenCodeServerState.REUSING,
        )
    }

    // ==================== 拉起自有实例 ====================

    private fun startOwnServer(config: OpenCodeServerConnectionConfig) {
        val requestedPorts = deps.resolver.bindPortCandidates(config.serverUrl)
        val portCandidates = when (val allocation = deps.portAllocator.allocate(requestedPorts)) {
            is OpenCodePortAllocation.Allocated ->
                // 首选端口优先，其余候选留作 TOCTOU 兜底（分配时空闲、启动时被他人抢占）
                listOf(allocation.port) + requestedPorts.filterNot { it == allocation.port }

            is OpenCodePortAllocation.Exhausted -> {
                fail(OpenCodeServerFailure.PORT_IN_USE, "候选端口均被占用：${allocation.attempts}")
                return
            }
        }
        val launchSpecBase = OpenCodeServerLaunchSpec(
            port = 0,
            workingDirectory = host.workingDirectory(),
            password = config.password,
            cliPath = config.cliPath,
        )
        val referenceId = deps.referenceId()

        for (port in portCandidates) {
            transition(OpenCodeServerState.STARTING, port = port, detail = "正在启动 OpenCode Server（端口 $port）")

            val handle = try {
                deps.launcher.launch(launchSpecBase.copy(port = port))
            } catch (_: OpenCodeServerCliNotFoundException) {
                fail(
                    OpenCodeServerFailure.CLI_NOT_FOUND,
                    "未找到 opencode CLI，请安装或在本插件设置中指定 CLI 路径",
                )
                return
            } catch (e: Exception) {
                fail(OpenCodeServerFailure.PROCESS_EXITED, "启动失败：${e.message}")
                return
            }

            val endpoint = OpenCodeServerEndpoint(
                baseUrl = OpenCodeServerUrls.loopback(port),
                username = config.username,
                password = config.password,
                port = port,
                source = OpenCodeServerEndpointSource.DEFAULT_PORT,
                owned = true,
            )

            when (val probe = deps.discovery.awaitReady(endpoint)) {
                is OpenCodeServerProbeResult.Ready -> {
                    // 端口争用双重校验：端口上的服务必须就是本进程的子进程（§3.3）
                    if (probe.info.pid != null && probe.info.pid != handle.pid) {
                        log.info("端口 $port 被其它进程占用（info.pid=${probe.info.pid} 子进程 pid=${handle.pid}），换端口重试")
                        handle.terminate()
                        continue
                    }
                    process = handle
                    ownedPort = port
                    deps.registry.registerOwned(
                        port = port,
                        pid = handle.pid,
                        baseUrl = endpoint.baseUrl,
                        pluginVersion = host.pluginVersion(),
                    )
                    val entry = deps.registry.acquire(port, referenceId)
                    handle.addTerminationListener { onProcessTerminated(port) }
                    publish(
                        status = OpenCodeServerStatus(
                            state = OpenCodeServerState.READY,
                            endpoint = endpoint,
                            port = port,
                            owned = true,
                            refCount = entry?.refCount ?: 1,
                        ),
                        state = OpenCodeServerState.READY,
                    )
                    healthFailureStreak = 0
                    return
                }

                is OpenCodeServerProbeResult.NotReady -> {
                    val alive = handle.isAlive
                    handle.terminate()
                    process = null
                    ownedPort = null
                    when {
                        probe.failure == OpenCodeServerFailure.AUTH_FAILED ->
                            fail(OpenCodeServerFailure.AUTH_FAILED, probe.detail)

                        !alive || probe.failure == OpenCodeServerFailure.PROCESS_EXITED -> {
                            fail(OpenCodeServerFailure.PROCESS_EXITED, probe.detail, failedProcess = handle)
                            return
                        }

                        else -> Unit // 端口冲突/超时：换下一个候选端口
                    }
                }
            }
        }

        fail(OpenCodeServerFailure.PORT_IN_USE, "所有候选端口均不可用：$portCandidates")
    }

    // ==================== 自愈 ====================

    private fun onProcessTerminated(port: Int) {
        if (disposed || port != ownedPort) return
        process = null
        ownedPort = null
        onServerDead("自有进程已退出", port = port)
    }

    private fun onServerDead(reason: String, port: Int? = null) {
        if (disposed) return
        if (!recordRestartAttempt()) {
            fail(OpenCodeServerFailure.PROCESS_EXITED, "$reason，且自愈额度已耗尽（$MAX_RESTARTS 次 / ${RESTART_WINDOW_MS / 60_000} 分钟）")
            return
        }
        _status.value = _status.value.copy(
            state = OpenCodeServerState.STARTING,
            detail = "$reason，正在重启",
            failure = null,
        )
        deps.executor {
            if (disposed) return@executor
            deps.sleep(RESTART_BACKOFF_MS)
            ensureStarted()
        }
    }

    private fun recordRestartAttempt(): Boolean {
        val now = System.currentTimeMillis()
        while (restartTimestamps.isNotEmpty() && now - restartTimestamps.first() > RESTART_WINDOW_MS) {
            restartTimestamps.removeFirst()
        }
        if (restartTimestamps.size >= MAX_RESTARTS) return false
        restartTimestamps.addLast(now)
        return true
    }

    // ==================== 状态与下发 ====================

    private fun publish(status: OpenCodeServerStatus, state: OpenCodeServerState) {
        _status.value = status
        log.info("Server 状态：$state port=${status.port} owned=${status.owned} refCount=${status.refCount}")
        status.endpoint?.let { endpoint ->
            if (lastPublishedUrl != endpoint.baseUrl) {
                lastPublishedUrl = endpoint.baseUrl
                host.onEndpointReady(endpoint)
            }
        }
    }

    private fun fail(
        failure: OpenCodeServerFailure,
        detail: String,
        candidate: OpenCodeServerEndpoint? = null,
        failedProcess: OpenCodeServerProcess? = null,
    ) {
        log.info("Server 失败：$failure detail=$detail")
        _status.value = OpenCodeServerStatus(
            state = OpenCodeServerState.FAILED,
            endpoint = candidate,
            failure = failure,
            detail = detail,
            port = candidate?.port,
            owned = false,
            outputTail = failedProcess?.outputBuffer?.snapshotTail(OUTPUT_TAIL_LINES) ?: emptyList(),
        )
    }

    private fun transition(
        state: OpenCodeServerState,
        endpoint: OpenCodeServerEndpoint? = _status.value.endpoint,
        port: Int? = _status.value.port,
        detail: String? = null,
    ) {
        _status.value = _status.value.copy(
            state = state,
            endpoint = endpoint,
            // 有了端点就一定有端口，避免状态里端口缺失（UI 文案要显示端口）
            port = port ?: endpoint?.port,
            detail = detail,
        )
        log.info("Server 状态：$state port=${_status.value.port} detail=$detail")
    }

    companion object {
        /** 自愈上限与窗口（§3.8） */
        const val MAX_RESTARTS = 3
        const val RESTART_WINDOW_MS = 10 * 60 * 1000L
        const val RESTART_BACKOFF_MS = 1_000L

        /** 健康检查连续失败阈值（§3.8） */
        const val HEALTH_FAILURE_THRESHOLD = 3

        /** FAILED 时展示的输出尾巴行数 */
        const val OUTPUT_TAIL_LINES = 30
    }
}
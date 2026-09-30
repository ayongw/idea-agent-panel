package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.server.OpenCodePortAllocator
import com.ayongw.idea.opencode.backend.server.OpenCodeServerCliNotFoundException
import com.ayongw.idea.opencode.backend.server.OpenCodeServerConnectionConfig
import com.ayongw.idea.opencode.backend.server.OpenCodeServerDiscovery
import com.ayongw.idea.opencode.backend.server.OpenCodeServerDeps
import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpoint
import com.ayongw.idea.opencode.backend.server.OpenCodeServerFailure
import com.ayongw.idea.opencode.backend.server.OpenCodeServerHost
import com.ayongw.idea.opencode.backend.server.OpenCodeServerInfo
import com.ayongw.idea.opencode.backend.server.OpenCodeServerLaunchSpec
import com.ayongw.idea.opencode.backend.server.OpenCodeServerLauncher
import com.ayongw.idea.opencode.backend.server.OpenCodeServerManager
import com.ayongw.idea.opencode.backend.server.OpenCodeServerOutputBuffer
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProcess
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProbePolicy
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProbeResult
import com.ayongw.idea.opencode.backend.server.OpenCodeServerRegistry
import com.ayongw.idea.opencode.backend.server.OpenCodeServerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 生命周期编排与自愈（TSD-31 §3.2 / §3.7 / §3.8）
 *
 * 全量替换协作者（假 Discovery / 假 Launcher / 假进程）+ 真实注册表（临时目录），覆盖：
 * 状态机关键转移、归属识别（注册表 + pid）、他人实例只复用不终止、`NEEDS_CREDENTIALS` 交互、
 * 端口争用换端口、CLI 缺失、就绪前退出、自愈上限、引用计数（关一个不停 / 关最后一个才停）、健康检查。
 */
class OpenCodeServerManagerUnitTest {

    private lateinit var tempDir: Path
    private lateinit var registry: OpenCodeServerRegistry
    private lateinit var discovery: ScriptedDiscovery
    private lateinit var launcher: ScriptedLauncher
    private lateinit var host: FakeHost

    private val createdManagers = mutableListOf<OpenCodeServerManager>()
    private val killedPids = mutableListOf<Long>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("opencode-manager")
        registry = OpenCodeServerRegistry(tempDir.resolve("server-registry.json"))
        discovery = ScriptedDiscovery()
        launcher = ScriptedLauncher()
        host = FakeHost(OpenCodeServerConnectionConfig(serverUrl = DEFAULT_URL, username = "opencode", password = null))
    }

    @After
    fun tearDown() {
        createdManagers.forEach { runCatching { it.dispose() } }
        createdManagers.clear()
        tempDir.toFile().deleteRecursively()
    }

    // ==================== 自动启动与复用 ====================

    @Test
    fun `无可用端点时自动拉起并下发一次端点`() {
        discovery.awaitReadyByPort[4096] = ready(pid = STUB_PID)
        val manager = newManager()

        manager.ensureStarted()

        val status = manager.status.value
        assertEquals(OpenCodeServerState.READY, status.state)
        assertTrue("拉起的实例应为本插件所有", status.owned)
        assertEquals(4096, status.port)
        assertEquals(1, status.refCount)
        assertEquals("端点变化才下发，且只下发一次", 1, host.published.size)
        assertEquals("http://127.0.0.1:4096", host.published.single().baseUrl)

        val entry = registry.findByPort(4096)
        assertNotNull("自有实例应写入共享注册表", entry)
        assertEquals(STUB_PID, entry!!.pid)
        assertEquals(1, entry.refCount)
    }

    @Test
    fun `注册表 pid 匹配时复用自有实例且不重复拉起`() {
        registry.registerOwned(4096, STUB_PID, DEFAULT_URL, "other-window")
        discovery.probeByPort[4096] = ready(STUB_PID)
        val manager = newManager()

        manager.ensureStarted()

        val status = manager.status.value
        assertEquals(OpenCodeServerState.REUSING, status.state)
        assertTrue(status.owned)
        assertTrue("不应再拉起新进程", launcher.launched.isEmpty())
        assertEquals("复用应执行引用计数 acquire", 1, registry.findByPort(4096)!!.refCount)
        assertEquals(1, host.published.size)
    }

    @Test
    fun `他人实例只复用不终止`() {
        discovery.probeByPort[4096] = ready(pid = 8888)
        val manager = newManager()

        manager.ensureStarted()

        assertEquals(OpenCodeServerState.REUSING, manager.status.value.state)
        assertFalse("他人实例不得标记为自有", manager.status.value.owned)
        assertFalse("REUSING 他人实例下停止必须被拒绝", manager.stopOwnedServer())

        manager.dispose()

        assertTrue("不得按 pid 终止他人进程", killedPids.isEmpty())
        assertTrue("不得写入注册表", registry.load().isEmpty())
    }

    @Test
    fun `他人实例缺凭据进入 NEEDS_CREDENTIALS 并可用密钥接入`() {
        discovery.probeByPort[4096] = notReady(OpenCodeServerFailure.AUTH_FAILED, retryable = false)
        val manager = newManager()

        manager.ensureStarted()
        assertEquals(OpenCodeServerState.NEEDS_CREDENTIALS, manager.status.value.state)
        assertEquals(4096, manager.status.value.port)

        assertFalse("凭据不符应保持等待", manager.submitCredentials("opencode", "wrong"))
        assertEquals(OpenCodeServerState.NEEDS_CREDENTIALS, manager.status.value.state)

        discovery.probeByPort[4096] = ready(pid = 8888)
        assertTrue("凭据通过应转入复用", manager.submitCredentials("opencode", "right"))
        assertEquals(OpenCodeServerState.REUSING, manager.status.value.state)
        assertEquals(1, host.published.size)
    }

    @Test
    fun `自有实例认证失败直接 FAILED 且不再尝试后续候选`() {
        registry.registerOwned(4096, STUB_PID, DEFAULT_URL, "v")
        discovery.probeByPort[4096] = notReady(OpenCodeServerFailure.AUTH_FAILED, retryable = false)
        val manager = newManager()

        manager.ensureStarted()

        val status = manager.status.value
        assertEquals(OpenCodeServerState.FAILED, status.state)
        assertEquals(OpenCodeServerFailure.AUTH_FAILED, status.failure)
        assertEquals("认证失败后不得继续探测备用端口", listOf(4096), discovery.probeCalls)
    }

    @Test
    fun `关闭自动启动且无可用端点时转 FAILED`() {
        host.config = host.config.copy(autoStartServer = false)
        val manager = newManager()

        manager.ensureStarted()

        assertEquals(OpenCodeServerState.FAILED, manager.status.value.state)
        assertEquals(OpenCodeServerFailure.UNREACHABLE, manager.status.value.failure)
        assertTrue("不得拉起进程", launcher.launched.isEmpty())
    }

    @Test
    fun `端口争用时丢弃子进程并换下一个候选端口`() {
        discovery.awaitReadyByPort[4096] = ready(pid = 9999)
        discovery.awaitReadyByPort[4097] = ready(pid = STUB_PID)
        val manager = newManager()

        manager.ensureStarted()

        assertEquals(OpenCodeServerState.READY, manager.status.value.state)
        assertEquals("应落到无争用的端口", 4097, manager.status.value.port)
        assertEquals(2, launcher.launched.size)
        assertFalse("被争用端口上的子进程应被丢弃", launcher.launched.first().isAlive)
        assertEquals("注册表只登记最终端口", 4097, registry.load().single().port)
    }

    @Test
    fun `CLI 缺失时转 FAILED 且分类为 CLI_NOT_FOUND`() {
        launcher.failWithCliNotFound = true
        val manager = newManager()

        manager.ensureStarted()

        assertEquals(OpenCodeServerState.FAILED, manager.status.value.state)
        assertEquals(OpenCodeServerFailure.CLI_NOT_FOUND, manager.status.value.failure)
        assertTrue("未启动成功时不得写入注册表", registry.load().isEmpty())
    }

    @Test
    fun `就绪前进程退出时转 FAILED 且分类为 PROCESS_EXITED`() {
        launcher.exitImmediately = true
        val manager = newManager()

        manager.ensureStarted()

        val status = manager.status.value
        assertEquals(OpenCodeServerState.FAILED, status.state)
        assertEquals(OpenCodeServerFailure.PROCESS_EXITED, status.failure)
        assertFalse("失败态不属于就绪", status.isReady)
    }

    @Test
    fun `改用插件自启实例时跳过探测直接拉起`() {
        // 4096 上有可聊通的他人实例，但用户显式选择自启 → 不得复用，直接拉起自有实例
        discovery.probeByPort[4096] = ready(pid = 8888)
        discovery.awaitReadyByPort[4096] = ready(pid = STUB_PID)
        val manager = newManager()

        manager.startOwnInstance()

        val status = manager.status.value
        assertEquals(OpenCodeServerState.READY, status.state)
        assertTrue("自启实例应标记为自有", status.owned)
        assertTrue("不得探测候选端点：${discovery.probeCalls}", discovery.probeCalls.isEmpty())
        assertEquals(1, launcher.launched.size)
    }

    // ==================== 自愈 ====================

    @Test
    fun `自有进程退出后自愈重启，超过额度转 FAILED`() {
        discovery.awaitReadyByPort[4096] = ready(STUB_PID)
        val manager = newManager()
        manager.ensureStarted()
        assertEquals(OpenCodeServerState.READY, manager.status.value.state)

        repeat(OpenCodeServerManager.MAX_RESTARTS) {
            launcher.last().exitExternally()
            assertEquals("额度内应重启成功", OpenCodeServerState.READY, manager.status.value.state)
        }

        launcher.last().exitExternally()

        assertEquals(OpenCodeServerState.FAILED, manager.status.value.state)
        assertEquals(OpenCodeServerFailure.PROCESS_EXITED, manager.status.value.failure)
        assertEquals(
            "额度用尽后不得再拉起",
            OpenCodeServerManager.MAX_RESTARTS + 1,
            launcher.launched.size,
        )
    }

    @Test
    fun `健康检查连续失败触发自愈`() {
        discovery.awaitReadyByPort[4096] = ready(STUB_PID)
        val manager = newManager()
        manager.ensureStarted()

        discovery.probeByPort[4096] = notReady(OpenCodeServerFailure.UNREACHABLE)
        repeat(OpenCodeServerManager.HEALTH_FAILURE_THRESHOLD - 1) {
            assertFalse(manager.checkHealth())
        }
        assertEquals("阈值内不应重启", 1, launcher.launched.size)

        assertFalse("第 ${OpenCodeServerManager.HEALTH_FAILURE_THRESHOLD} 次失败即触发自愈", manager.checkHealth())
        assertEquals("应重新拉起", 2, launcher.launched.size)
        assertEquals(OpenCodeServerState.READY, manager.status.value.state)
    }

    // ==================== 引用计数与终止 ====================

    @Test
    fun `引用计数关一个不停关最后一个才停`() {
        discovery.awaitReadyByPort[4096] = ready(STUB_PID)
        val firstWindow = newManager(referenceId = "A")
        firstWindow.ensureStarted()
        assertEquals(1, registry.findByPort(4096)!!.refCount)

        discovery.probeByPort[4096] = ready(STUB_PID)
        val secondWindow = newManager(referenceId = "B")
        secondWindow.ensureStarted()
        assertEquals(
            "两个窗口应各自登记一个引用",
            listOf("A", "B"),
            registry.findByPort(4096)!!.references.map { it.referenceId }.sorted(),
        )

        firstWindow.dispose()
        assertEquals(
            "dispose 后应仍保留一条引用：${registry.findByPort(4096)?.references?.map { it.referenceId }}",
            1,
            registry.findByPort(4096)?.refCount ?: -1,
        )
        assertEquals("只应有 1 个子进程", 1, launcher.launched.size)
        assertTrue("仍有引用者时不得终止进程", launcher.last().isAlive)
        assertTrue("关一个窗口不得按 pid 终止：$killedPids", killedPids.isEmpty())

        secondWindow.dispose()
        assertTrue("最后一个引用者应按 pid 终止共享进程", killedPids.contains(STUB_PID))
        assertTrue("注册表条目应被清理", registry.load().isEmpty())
    }

    @Test
    fun `复用自有实例时单窗口关闭不终止`() {
        registry.registerOwned(4096, STUB_PID, DEFAULT_URL, "other-window")
        registry.acquire(4096, "other-ref")
        discovery.probeByPort[4096] = ready(STUB_PID)
        val manager = newManager(referenceId = "mine")

        manager.ensureStarted()
        assertEquals(2, registry.findByPort(4096)!!.refCount)

        manager.dispose()

        assertTrue("还有别的引用者，不得终止他窗口拉起的进程", killedPids.isEmpty())
        assertEquals(1, registry.findByPort(4096)!!.refCount)
    }

    @Test
    fun `用户显式停止自有实例会终止进程并清空注册表`() {
        discovery.awaitReadyByPort[4096] = ready(STUB_PID)
        val manager = newManager()
        manager.ensureStarted()

        assertTrue(manager.stopOwnedServer())

        assertFalse(launcher.last().isAlive)
        assertEquals(OpenCodeServerState.STOPPED, manager.status.value.state)
        assertTrue(registry.load().isEmpty())
    }

    // ==================== 夹具 ====================

    private fun newManager(referenceId: String = "test-ref"): OpenCodeServerManager {
        val manager = OpenCodeServerManager(
            host = host,
            deps = OpenCodeServerDeps(
                discovery = discovery,
                launcher = launcher,
                registry = registry,
                portAllocator = OpenCodePortAllocator { true },
                referenceId = { referenceId },
                executor = { task -> task() },
                sleep = { },
                isProcessAlive = { true },
                killByPid = { pid -> killedPids += pid; true },
            ),
        )
        createdManagers += manager
        return manager
    }

    private fun ready(pid: Long) = OpenCodeServerProbeResult.Ready(OpenCodeServerInfo(pid = pid, version = "stub"))

    private fun notReady(failure: OpenCodeServerFailure, retryable: Boolean = true) =
        OpenCodeServerProbeResult.NotReady(failure = failure, detail = failure.name, retryable = retryable)

    private class FakeHost(var config: OpenCodeServerConnectionConfig) : OpenCodeServerHost {
        val published = mutableListOf<OpenCodeServerEndpoint>()

        override fun connectionConfig(): OpenCodeServerConnectionConfig = config

        override fun workingDirectory(): Path? = null

        override fun onEndpointReady(endpoint: OpenCodeServerEndpoint) {
            published += endpoint
        }

        override fun pluginVersion(): String = "test-version"
    }

    /** 按端口脚本化的探测：默认「连接被拒」（触发自动启动） */
    private class ScriptedDiscovery : OpenCodeServerDiscovery() {
        val probeByPort = mutableMapOf<Int, OpenCodeServerProbeResult>()
        val awaitReadyByPort = mutableMapOf<Int, OpenCodeServerProbeResult>()
        val probeCalls = mutableListOf<Int>()

        private val defaultProbe: OpenCodeServerProbeResult =
            OpenCodeServerProbeResult.NotReady(OpenCodeServerFailure.UNREACHABLE, "连接被拒")

        override fun probe(endpoint: OpenCodeServerEndpoint, policy: OpenCodeServerProbePolicy): OpenCodeServerProbeResult {
            probeCalls += endpoint.port
            return probeByPort[endpoint.port] ?: defaultProbe
        }

        override fun awaitReady(endpoint: OpenCodeServerEndpoint, policy: OpenCodeServerProbePolicy): OpenCodeServerProbeResult =
            awaitReadyByPort[endpoint.port]
                ?: OpenCodeServerProbeResult.NotReady(OpenCodeServerFailure.READY_TIMEOUT, "未就绪")
    }

    private class ScriptedLauncher : OpenCodeServerLauncher() {
        val launched = mutableListOf<FakeProcess>()
        var failWithCliNotFound = false
        var exitImmediately = false

        override fun launch(spec: OpenCodeServerLaunchSpec): OpenCodeServerProcess {
            if (failWithCliNotFound) throw OpenCodeServerCliNotFoundException("cli missing")
            val process = FakeProcess(pid = STUB_PID, port = spec.port)
            launched += process
            if (exitImmediately) process.exitExternally()
            return process
        }

        fun last(): FakeProcess = launched.last()
    }

    private class FakeProcess(override val pid: Long, private val port: Int) : OpenCodeServerProcess {
        override val outputBuffer = OpenCodeServerOutputBuffer()
        private val listeners = mutableListOf<() -> Unit>()

        @Volatile
        private var alive = true

        override val isAlive: Boolean get() = alive

        override fun awaitExit(timeoutMs: Long): Boolean = !alive

        override fun terminate(graceMs: Long) = exitExternally()

        override fun addTerminationListener(listener: () -> Unit) {
            listeners += listener
            if (!alive) listener()
        }

        fun exitExternally() {
            if (!alive) return
            alive = false
            outputBuffer.appendLine("process exited with code 0 port=$port")
            listeners.toList().forEach { it() }
        }
    }

    companion object {
        private const val DEFAULT_URL = "http://127.0.0.1:4096"
        private const val STUB_PID = 4242L
    }
}
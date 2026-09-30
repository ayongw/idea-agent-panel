package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.server.OpenCodeServerCliLocator
import com.ayongw.idea.opencode.backend.server.OpenCodeServerCliNotFoundException
import com.ayongw.idea.opencode.backend.server.OpenCodeServerDiscovery
import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpoint
import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpointSource
import com.ayongw.idea.opencode.backend.server.OpenCodeServerFailure
import com.ayongw.idea.opencode.backend.server.OpenCodeServerLaunchSpec
import com.ayongw.idea.opencode.backend.server.OpenCodeServerLauncher
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProcess
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProbePolicy
import com.ayongw.idea.opencode.backend.server.OpenCodeServerProbeResult
import com.intellij.testFramework.TestApplicationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/**
 * 真实子进程的拉起 / 输出采集 / 终止（TSD-31 §10.1，用 JVM 桩进程代替真实 `opencode`）
 *
 * 默认不执行（`./gradlew test` 排除 `*ITest`）；改动进程链路时用 `./gradlew test -Pit=true` 运行。
 */
class OpenCodeServerLauncherITest {

    private val launched = mutableListOf<OpenCodeServerProcess>()

    @After
    fun terminateAll() {
        launched.forEach { runCatching { it.terminate(1_000) } }
        launched.clear()
    }

    @Test
    fun `桩进程可拉起并采集输出`() {
        val port = freePort()

        val handle = launchStub(port)

        assertTrue("子进程应存活", handle.isAlive)
        assertTrue("应能取到子进程 pid", handle.pid > 0)
        assertTrue("应采集到桩进程输出", waitForOutput(handle, "listening", 5_000))
    }

    @Test
    fun `就绪探测拿到的 pid 与子进程一致`() {
        val port = freePort()
        val handle = launchStub(port)

        val result = OpenCodeServerDiscovery().awaitReady(endpoint(port), fastPolicy())

        assertTrue("应就绪，实际 $result", result is OpenCodeServerProbeResult.Ready)
        assertEquals(
            "归属判定的双重校验：info.pid 必须等于子进程 pid",
            handle.pid,
            (result as OpenCodeServerProbeResult.Ready).info.pid,
        )
    }

    @Test
    fun `终止后进程不再存活`() {
        val port = freePort()
        val handle = launchStub(port)
        val pid = handle.pid
        assertTrue(waitForOutput(handle, "listening", 5_000))

        handle.terminate(3_000)

        assertFalse("终止后进程句柄应判定为已退出", handle.isAlive)
        assertTrue("应能在超时内等到退出", handle.awaitExit(5_000))
        assertTrue("退出回调应被触发", waitUntil(3_000) { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) })
    }

    @Test
    fun `就绪前退出的进程被判定为已终止`() {
        val port = freePort()

        val handle = launchStub(port, mode = "exit")

        assertTrue("桩应自行退出", handle.awaitExit(10_000))
        assertFalse(handle.isAlive)
        assertTrue("退出码应进入输出缓冲", handle.outputBuffer.snapshot().any { it.contains("process exited") })
    }

    @Test
    fun `真实桩返回 401 时分类为认证失败`() {
        val port = freePort()
        val handle = launchStub(port, mode = "unauthorized")
        assertTrue("桩应先就绪", waitForOutput(handle, "stub listening", 10_000))

        val result = OpenCodeServerDiscovery().probe(endpoint(port))

        assertTrue(result is OpenCodeServerProbeResult.NotReady)
        assertEquals(OpenCodeServerFailure.AUTH_FAILED, (result as OpenCodeServerProbeResult.NotReady).failure)
        assertFalse("认证失败不应重试", result.retryable)
    }

    @Test
    fun `真实桩慢启动时短超时判为不可达`() {
        val port = freePort()
        val handle = launchStub(port, mode = "slow")
        assertTrue("桩应先就绪", waitForOutput(handle, "stub listening", 10_000))

        val result = OpenCodeServerDiscovery().probe(endpoint(port), fastPolicy().copy(readTimeoutMs = 300))

        assertTrue(result is OpenCodeServerProbeResult.NotReady)
        assertEquals(OpenCodeServerFailure.UNREACHABLE, (result as OpenCodeServerProbeResult.NotReady).failure)
        assertTrue("慢启动应可重试", result.retryable)
    }

    @Test
    fun `CLI 不存在时抛出 CLI 未找到`() {
        val launcher = OpenCodeServerLauncher(
            cliLocator = OpenCodeServerCliLocator(pathEnv = null, isExecutable = { false }),
        )

        assertThrows(OpenCodeServerCliNotFoundException::class.java) {
            launcher.launch(OpenCodeServerLaunchSpec(port = freePort()))
        }
    }

    // ==================== 夹具 ====================

    private fun launchStub(port: Int, mode: String = "ok"): OpenCodeServerProcess {
        val handle = launcher(mode).launch(
            OpenCodeServerLaunchSpec(
                port = port,
                workingDirectory = File(".").absoluteFile.toPath(),
                cliPath = javaBinary(),
            ),
        )
        launched += handle
        return handle
    }

    /** 用 JVM 自身拉起桩进程（跨平台，无需 shell）；`--mode` 决定桩形态 */
    private fun launcher(mode: String): OpenCodeServerLauncher = OpenCodeServerLauncher(
        // cliPath 由夹具显式给出（java 可执行文件），故可执行性判定一律放行
        cliLocator = OpenCodeServerCliLocator(pathEnv = null, isExecutable = { true }),
        commandFactory = { spec, cliPath ->
            listOf(
                cliPath,
                "-cp",
                System.getProperty("java.class.path"),
                STUB_MAIN_CLASS,
                "--port",
                spec.port.toString(),
                "--mode",
                mode,
            )
        },
    )

    private fun endpoint(port: Int) = OpenCodeServerEndpoint(
        baseUrl = "http://127.0.0.1:$port",
        username = "opencode",
        password = null,
        port = port,
        source = OpenCodeServerEndpointSource.DEFAULT_PORT,
        owned = true,
    )

    private fun fastPolicy() = OpenCodeServerProbePolicy(
        connectTimeoutMs = 1_000,
        readTimeoutMs = 1_000,
        totalTimeoutMs = 10_000,
    )

    private fun waitForOutput(handle: OpenCodeServerProcess, needle: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { handle.outputBuffer.snapshot().any { it.contains(needle) } }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun javaBinary(): String = File(System.getProperty("java.home"), "bin/java").absolutePath

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val STUB_MAIN_CLASS = "com.ayongw.idea.opencode.server.stub.OpenCodeServerStubMain"

        /** `KillableProcessHandler` 需要平台 Application 存在 */
        @JvmStatic
        @BeforeClass
        fun initPlatform() {
            TestApplicationManager.getInstance()
        }
    }
}
package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.mcp.LoginShellPath
import com.ayongw.idea.opencode.backend.server.OpenCodeServerCliLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CLI 路径解析（TSD-31 §3.6）：设置项覆盖优先、PATH 命中、未命中返回 null。
 */
class OpenCodeServerCliLocatorUnitTest {

    private fun locator(
        path: String?,
        executables: Set<String>,
        osName: String = "Mac OS X",
    ) = OpenCodeServerCliLocator(
        pathEnv = { path },
        isExecutable = { it in executables },
        osName = osName,
    )

    @Test
    fun `设置项指定且可执行时优先使用设置项`() {
        val resolved = locator(
            path = "/usr/local/bin",
            executables = setOf("/custom/opencode", "/usr/local/bin/opencode"),
        ).locate("/custom/opencode")

        assertEquals("/custom/opencode", resolved)
    }

    @Test
    fun `设置项不可执行时视为未找到且不回落到 PATH`() {
        val resolved = locator(
            path = "/usr/local/bin",
            executables = setOf("/usr/local/bin/opencode"),
        ).locate("/custom/opencode")

        assertNull("显式配置不可用时必须报错，避免静默换成另一个二进制", resolved)
    }

    @Test
    fun `从 PATH 的多段中按顺序命中`() {
        val resolved = locator(
            path = listOf("/a", "/b", "/c").joinToString(File.pathSeparator),
            executables = setOf("/b/opencode"),
        ).locate(null)

        assertEquals("/b/opencode", resolved)
    }

    @Test
    fun `PATH 中没有 CLI 时返回 null`() {
        assertNull(locator(path = "/a:/b", executables = emptySet()).locate(null))
        assertNull("PATH 为空视为未找到", locator(path = null, executables = emptySet()).locate(null))
        assertNull("空白设置项视为未配置", locator(path = null, executables = emptySet()).locate("   "))
    }

    @Test
    fun `Windows 下优先命中 cmd 形态`() {
        val probed = mutableListOf<String>()
        val resolved = OpenCodeServerCliLocator(
            pathEnv = { "C:\\tools" },
            isExecutable = { probed += it; it.endsWith("opencode.cmd") },
            osName = "Windows 11",
        ).locate(null)

        assertTrue("应命中 .cmd 形态：$resolved", resolved!!.endsWith("opencode.cmd"))
        assertTrue("探测顺序应把 .cmd 放最前：$probed", probed.first().endsWith("opencode.cmd"))
    }

    @Test
    fun `非 Windows 不尝试 cmd 形态`() {
        val probedNames = mutableListOf<String>()
        OpenCodeServerCliLocator(
            pathEnv = { "/usr/bin" },
            isExecutable = { probedNames += it; false },
            osName = "Mac OS X",
        ).locate(null)

        assertTrue("只应探测无后缀形态", probedNames.all { it.endsWith("/opencode") })
    }

    @Test
    fun `PATH 惰性取值，构造期不触发探测`() {
        var calls = 0
        val lazy = OpenCodeServerCliLocator(
            pathEnv = { calls++; "/shell/bin" },
            isExecutable = { it == "/shell/bin/opencode" },
        )

        assertEquals("构造期不应取 PATH（避免启动即阻塞在 shell 探测）", 0, calls)
        assertEquals("/shell/bin/opencode", lazy.locate(null))
        assertEquals("命中后应已取值一次", 1, calls)
    }

    /**
     * 生产装配：`PATH` 取登录 shell 的（[LoginShellPath.effectivePath]），
     * 覆盖「CLI 装在 `~/.opencode/bin`，只在登录 shell PATH 里」的场景。
     */
    @Test
    fun `登录 shell PATH 中的 CLI 可被定位`() {
        val binDir = Files.createTempDirectory("opencode-cli").toFile()
        val executable = File(binDir, "opencode").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }

        val locator = OpenCodeServerCliLocator(
            pathEnv = { LoginShellPath(shell = "/bin/zsh", probe = { binDir.absolutePath }).effectivePath() },
        )

        assertEquals(executable.absolutePath, locator.locate(null))
    }
}
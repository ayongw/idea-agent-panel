package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.server.OpenCodeServerCliLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLI 路径解析（TSD-31 §3.6）：设置项覆盖优先、PATH 命中、未命中返回 null。
 */
class OpenCodeServerCliLocatorUnitTest {

    private fun locator(
        path: String?,
        executables: Set<String>,
        osName: String = "Mac OS X",
    ) = OpenCodeServerCliLocator(
        pathEnv = path,
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
            pathEnv = "C:\\tools",
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
            pathEnv = "/usr/bin",
            isExecutable = { probedNames += it; false },
            osName = "Mac OS X",
        ).locate(null)

        assertTrue("只应探测无后缀形态", probedNames.all { it.endsWith("/opencode") })
    }
}
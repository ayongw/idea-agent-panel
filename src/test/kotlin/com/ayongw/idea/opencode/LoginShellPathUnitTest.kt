package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.mcp.LoginShellPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IDE 进程 PATH 很窄（Finder 启动时只有 /usr/bin:/bin:...），裸命令要从登录 shell 的 PATH 里找
 */
class LoginShellPathUnitTest {

    @Test
    fun resolveTakesLastPathLikeLineFromInteractiveShellOutput() {
        // 交互 shell 可能先打印横幅，PATH 是最后一行
        val shellPath = LoginShellPath(shell = "/bin/zsh", probe = {
            "welcome to zsh\n/Users/me/.nvm/bin:/usr/local/bin:/usr/bin\n"
        })

        assertEquals("/Users/me/.nvm/bin:/usr/local/bin:/usr/bin", shellPath.resolve())
    }

    @Test
    fun resolveReturnsNullWhenShellMissingOrProbeFails() {
        assertNull("未取到 SHELL 不探测", LoginShellPath(shell = null, probe = { "x:y" }).resolve())
        assertNull("空白 SHELL 不探测", LoginShellPath(shell = "  ", probe = { "x:y" }).resolve())
        assertNull("探测抛异常按失败处理", LoginShellPath(shell = "/bin/zsh") { error("boom") }.resolve())
        assertNull("输出里没有 PATH 行", LoginShellPath(shell = "/bin/zsh", probe = { "   " }).resolve())
    }

    @Test
    fun resolveProbesAtMostOncePerSession() {
        var calls = 0
        val shellPath = LoginShellPath(shell = "/bin/zsh") {
            calls++
            "/nvm/bin"
        }

        assertEquals("/nvm/bin", shellPath.resolve())
        assertEquals("/nvm/bin", shellPath.resolve())

        assertEquals("同一会话只探测一次", 1, calls)
    }

    @Test
    fun effectivePathPutsLoginShellEntriesFirstAndDeduplicates() {
        val shellPath = LoginShellPath(shell = "/bin/zsh", probe = { "/nvm/bin:/opt/homebrew/bin" })

        // 登录 shell 的条目在前（与终端一致），进程自身 PATH 兜底，重复目录只留一份
        assertEquals(
            "/nvm/bin:/opt/homebrew/bin:/usr/bin:/bin",
            shellPath.effectivePath("/usr/bin:/bin:/nvm/bin")
        )
    }

    @Test
    fun effectivePathFallsBackToProcessPathWhenProbeUnavailable() {
        val shellPath = LoginShellPath(shell = null, probe = { null })

        assertEquals("/usr/bin:/bin", shellPath.effectivePath("/usr/bin:/bin"))
        assertNull("两者都没有时返回 null（保持继承原样）", shellPath.effectivePath("  "))
    }
}
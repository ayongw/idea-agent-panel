package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.vcs.GitCliHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * git CLI 读取分支 / 最近提交（2026.2 的 IDE 旧 VCS API 已失效，见 GitCliHistory 注释）。
 *
 * 解析逻辑用纯函数测；命令执行部分在**本仓库自身**上跑 —— 它就是一个真实 git 仓库，
 * 因此可以断言"能取到分支名""能取到历史""数量受 limit 约束"，无需构造临时仓库。
 */
class GitCliHistoryUnitTest {

    /** 本仓库路径（测试运行时的 cwd 即仓库根） */
    private val repoRoot: File = File(System.getProperty("user.dir"))

    // ==================== 纯解析 ====================

    @Test
    fun 解析提交标题忽略空行() {
        val out = """
            feat: 增加删除按钮

            fix(chat): 修复空白
            
            chore: 提交
        """.trimIndent()

        assertEquals(
            listOf("feat: 增加删除按钮", "fix(chat): 修复空白", "chore: 提交"),
            GitCliHistory.parseSubjects(out)
        )
    }

    @Test
    fun 解析空输出得空列表() {
        assertTrue(GitCliHistory.parseSubjects("").isEmpty())
        assertTrue(GitCliHistory.parseSubjects("   \n\n  ").isEmpty())
    }

    @Test
    fun 解析保留每行完整内容不做截断() {
        // 长标题（Conventional Commits 带 body 前缀）必须完整保留，否则模型学不到真实风格
        val long = "refactor(frontend)!: 重构会话列表渲染层以支持按打开状态分组展示"
        assertEquals(listOf(long), GitCliHistory.parseSubjects(long))
    }

    // ==================== 命令执行（本仓库实测） ====================

    @Test
    fun 能识别当前目录是Git仓库() {
        assertTrue("本仓库应为 git 仓库", GitCliHistory.isGitRepo(repoRoot))
    }

    @Test
    fun 能读到分支名() {
        val branch = GitCliHistory.currentBranch(repoRoot)
        assertNotNull("应能读到分支名", branch)
        assertTrue("分支名不应为空：$branch", branch!!.isNotBlank())
    }

    @Test
    fun 能读到最近提交且受limit约束() {
        val subjects = GitCliHistory.recentSubjects(repoRoot, limit = 3)
        assertTrue("应取到最近提交", subjects.isNotEmpty())
        assertTrue("数量不应超过 limit：$subjects", subjects.size <= 3)
        subjects.forEach { assertTrue("提交标题不应为空", it.isNotBlank()) }
    }

    @Test
    fun limit为零或负数返回空列表() {
        assertTrue(GitCliHistory.recentSubjects(repoRoot, limit = 0).isEmpty())
        assertTrue(GitCliHistory.recentSubjects(repoRoot, limit = -1).isEmpty())
    }

    @Test
    fun 非Git目录与非目录都安全返回空() {
        // 不抛异常即为要求：非仓库 / 不存在的目录都应安静退化
        GitCliHistory.isGitRepo(File("/"))
        GitCliHistory.currentBranch(File("/"))
        GitCliHistory.recentSubjects(File("/"))
        GitCliHistory.currentBranch(File("/definitely/not/exists/xxx"))
    }
}
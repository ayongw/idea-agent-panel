package com.ayongw.idea.agentpanel.frontend.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 读取 Git 分支名与最近提交信息（**git CLI**）。
 *
 * 为什么不用 IDE API（2026.2 实测）：
 * - `VcsRepositoryManager`（`intellij.platform.vcs.dvcs.impl`）已**没有** revision / branch 方法；
 * - `VcsLog`（`intellij.platform.vcs.log`）只暴露"选中项"，遍历历史要 `VcsLogManager`（`.log.impl`）。
 * 那几个 impl 模块作为 compile 依赖能解析，但旧 API 已失效 —— 留着只是死依赖 + 脆弱性。
 *
 * 这两项本来就只是"让模型模仿仓库既有语气"的辅助信息，缺失不影响生成主流程，
 * 因此用 git CLI 退化实现最稳（也与插件其它地方起 opencode CLI 的做法一致）。
 *
 * **线程约束**：所有方法都会起子进程，**禁止在 EDT 调用**。
 */
object GitCliHistory {

    /** 命令超时：git 本地调用很快，超时只用于兜住"卡住的仓库"（大仓库 gc 锁等） */
    private const val DEFAULT_TIMEOUT_MS = 5_000

    /** PATH 找不到 git 时的兜底路径（macOS / 常见 Linux 安装位置） */
    private val FALLBACK_GIT_PATHS = listOf(
        "/usr/bin/git",
        "/usr/local/bin/git",
        "/opt/homebrew/bin/git",
        "/usr/bin/git.exe"
    )

    /** 是否是 Git 仓库（目录或父目录） */
    fun isGitRepo(workingDir: File): Boolean = run(
        listOf("rev-parse", "--is-inside-work-tree"), workingDir
    )?.trim() == "true"

    /** 当前分支名；detached HEAD 时返回短 SHA 前缀；非仓库返回 null */
    fun currentBranch(workingDir: File, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? {
        val branch = run(listOf("rev-parse", "--abbrev-ref", "HEAD"), workingDir, timeoutMs)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
        if (branch != null) return branch
        // detached HEAD：退化为短 SHA
        return run(listOf("rev-parse", "--short", "HEAD"), workingDir, timeoutMs)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** 最近若干条提交标题（只看标题行，正文对"学语气"没价值） */
    fun recentSubjects(
        workingDir: File,
        limit: Int = 5,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): List<String> {
        if (limit <= 0) return emptyList()
        val out = run(
            listOf("log", "-$limit", "--no-merges", "--format=%s"),
            workingDir,
            timeoutMs
        ) ?: return emptyList()
        return parseSubjects(out)
    }

    /** 解析 `git log --format=%s` 的输出（抽出为纯函数便于单测） */
    fun parseSubjects(stdout: String): List<String> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** 执行 git 命令；任何失败（无 git / 非仓库 / 超时 / 非 0 退出）都返回 null，不抛异常 */
    private fun run(args: List<String>, workingDir: File, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? {
        if (!workingDir.isDirectory) return null
        for (executable in listOf("git") + FALLBACK_GIT_PATHS) {
            val output = execute(executable, args, workingDir, timeoutMs) ?: continue
            return output
        }
        return null
    }

    private fun execute(
        executable: String,
        args: List<String>,
        workingDir: File,
        timeoutMs: Int
    ): String? = runCatching {
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withWorkDirectory(workingDir)
            .withCharset(StandardCharsets.UTF_8)
            .withRedirectErrorStream(false)
        val handler = CapturingProcessHandler(commandLine)
        val result = handler.runProcess(timeoutMs)
        // 超时被 kill 时 isTimeout 为 true；非 0 退出且无 stdout 视为失败
        // （stderr 不外泄到业务日志，避免把仓库路径等噪音带进日志）
        if (result.isTimeout) return null
        if (result.exitCode != 0 && result.stdout.isBlank()) return null
        result.stdout
    }.getOrNull()
}
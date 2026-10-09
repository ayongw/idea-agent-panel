package com.ayongw.idea.agentpanel.frontend.vcs

import java.io.File
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber

/**
 * 从 VCS 采集提交上下文（IO 层）。
 *
 * 只负责"取数"，限长与 prompt 组成交给纯函数（[CommitMessageDiffLimiter] /
 * [CommitMessagePromptBuilder]），便于单测覆盖边界而无需真实仓库。
 *
 * 2026.2 的 `Change` 已改为 Workspace Model 实现（`affectedFile` 已移除），
 * 路径取自 `beforeRevision` / `afterRevision` 的 [ContentRevision.getFile]。
 *
 * **分支名 / 最近提交走 git CLI**（[GitCliHistory]）：2026.2 里 IDE 的旧 VCS API 已失效——
 * `VcsRepositoryManager`（`intellij.platform.vcs.dvcs.impl`）已**没有任何 revision/branch 方法**
 * （实测 24 个 public 方法全无）；`VcsLog`（`intellij.platform.vcs.log`）只暴露"选中项"，
 * 遍历历史要 `VcsLogManager`（`.log.impl`）。那些 impl 模块虽能作为 compile 依赖解析，
 * 但留着只是死依赖 + 脆弱性，故不引入；改用 git CLI 退化实现。
 *
 * ⚠️ git CLI 会起子进程，**本类必须在 IO 线程调用**。
 */
object CommitMessageContextCollector {

    /** 单文件内容读取上限：超过则只给路径与状态，不取正文（避免大文件卡住） */
    private const val MAX_CONTENT_LINES = 5_000

    /** 最近提交条数（只用于学语气，3~5 条足够） */
    private const val DEFAULT_RECENT_COMMIT_LIMIT = 5


    /**
     * 采集变更上下文。
     *
     * @param changes 提交窗口当前选中的变更（`VcsDataKeys.CHANGES`）
     * @param includeUnstaged 预留：区分"暂存区快照"与"工作区快照"两种口径
     *   （当前选中项已含未暂存改动，暂不改变采集行为）
     */
    fun collect(
        basePath: String?,
        changes: List<Change>,
        includeUnstaged: Boolean,
        recentCommitLimit: Int = DEFAULT_RECENT_COMMIT_LIMIT
    ): CommitMessageContext {
        val raw = changes.mapNotNull { it.toChange() }
        val totalLines = raw.sumOf { it.diff?.lines()?.size ?: 0 }

        // 分支 / 最近提交：走 git CLI（IDE 的旧 VCS API 在 2026.2 已失效，见类注释）
        val dir = basePath?.let { File(it) }?.takeIf { it.isDirectory }
        val gitAvailable = dir != null && GitCliHistory.isGitRepo(dir)

        return CommitMessageContext(
            branch = if (gitAvailable) GitCliHistory.currentBranch(dir!!) else null,
            recentCommitMessages = if (gitAvailable) {
                GitCliHistory.recentSubjects(dir, recentCommitLimit)
            } else {
                emptyList()
            },
            changes = CommitMessageDiffLimiter.limit(raw),
            totalDiffLinesBeforeLimit = totalLines
        )
    }

    /** 变更 → 上下文条目；取不到路径的变更直接丢弃 */
    private fun Change.toChange(): CommitMessageChange? {
        val path = (afterRevision?.file ?: beforeRevision?.file)?.path ?: return null
        val before = beforeRevision?.contentOrNull()
        val after = afterRevision?.contentOrNull()

        // 删除文件：after 为空，用 before 全量（带 - 前缀）
        val diff = when {
            after != null -> after
            before != null -> before.lines().joinToString("\n") { "-$it" }
            else -> null
        }

        val diffLines = diff?.lines().orEmpty()
        // 新增文件：全部算新增；修改/删除：按 diff 里的 - 前缀行数算删除
        val added = if (before == null && after != null) diffLines.size else 0
        val removed = diffLines.count { it.startsWith("-") }

        return CommitMessageChange(
            path = path,
            // FileStatus 无单字符 code（只有 getText/getId），取 id 首字符作为展示类型
            changeType = fileStatus?.id?.firstOrNull()?.toString() ?: "?",
            addedLines = added,
            removedLines = removed,
            diff = diff
        )
    }

    private fun ContentRevision.contentOrNull(): String? = runCatching {
        if (revisionNumber == VcsRevisionNumber.NULL) return@runCatching null
        // ContentRevision.getContent() 在 Kotlin 侧是可空（内容可能未加载）
        val text = content ?: return@runCatching null
        if (text.lineSequence().take(MAX_CONTENT_LINES).count() > MAX_CONTENT_LINES) return@runCatching null
        text
    }.getOrNull()
}
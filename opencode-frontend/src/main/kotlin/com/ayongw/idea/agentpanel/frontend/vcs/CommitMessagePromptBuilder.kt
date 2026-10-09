package com.ayongw.idea.agentpanel.frontend.vcs

/**
 * 提交信息 prompt 组装（纯函数，无 VCS / IO 依赖）。
 *
 * 组装原则：
 * - 明说 diff 是**节选**，避免模型把截断后的内容当成全貌；
 * - 变更文件列表给全量（只含路径 + 统计，很便宜），diff 才限量；
 * - 最近提交信息用于让模型模仿仓库既有的语气/前缀风格，而不是自己发明格式。
 */
object CommitMessagePromptBuilder {

    /** 提交信息语言选项 */
    enum class Language(val label: String) {
        CHINESE("中文"),
        ENGLISH("English")
    }

    /**
     * 组装 prompt。
     *
     * @param context 已限长的上下文
     * @param language 正文语言
     * @param includeUnstaged 是否纳入未暂存变更（由调用方决定放进哪些 change）
     */
    fun build(
        context: CommitMessageContext,
        language: Language = Language.CHINESE,
        includeUnstaged: Boolean = true
    ): String = buildString {
        appendLine("为下列代码变更生成一条 Git 提交信息。")
        appendLine()
        appendLine("要求：")
        appendLine("- 使用 Conventional Commits 格式：`<type>(<scope>): <subject>`")
        appendLine("- 正文使用${language.label}")
        appendLine("- 只描述做了什么、为什么；不要写客套话与重复标题")
        appendLine("- 若改动跨多个关注点，type 取最主要的那个")
        appendLine()

        context.branch?.takeIf { it.isNotBlank() }?.let {
            appendLine("当前分支：$it")
            appendLine()
        }

        if (context.recentCommitMessages.isNotEmpty()) {
            appendLine("最近的提交（模仿其语气与前缀风格，不要照抄内容）：")
            context.recentCommitMessages.forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("变更文件（${context.changes.size} 个）：")
        context.changes.forEach { change ->
            appendLine("- ${change.changeType}  ${change.path}  (+${change.addedLines} -${change.removedLines})")
        }
        appendLine()

        val withDiff = context.changes.filter { !it.diff.isNullOrBlank() }
        if (withDiff.isEmpty()) {
            appendLine("（本次仅提供行数统计，未提供 diff 正文。）")
        } else {
            appendLine("diff 节选（**仅为节选**，未列出的部分不代表没有改动）：")
            withDiff.forEach { change ->
                appendLine()
                appendLine("--- ${change.path}")
                appendLine(change.diff!!.trimEnd())
                if (change.diffTruncated) {
                    appendLine("…（该文件 diff 已截断）")
                }
            }
            val statOnlyCount = context.changes.count { it.statOnly }
            if (statOnlyCount > 0) {
                appendLine()
                appendLine("（另有 $statOnlyCount 个文件改动较大或额度已用尽，本次仅提供行数统计。）")
            }
        }

        if (context.totalDiffLinesBeforeLimit > 0 && withDiff.isNotEmpty()) {
            appendLine()
            appendLine(
                "（原始 diff 合计 ${context.totalDiffLinesBeforeLimit} 行，" +
                    "已按上限截断；请只依据可见部分推断，并在描述涉及范围时保持笼统。）"
            )
        }

        appendLine()
        appendLine("只输出提交信息文本本身，不要加解释、不要加代码块围栏。")
    }
}
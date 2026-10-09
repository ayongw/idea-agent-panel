package com.ayongw.idea.opencode.frontend.vcs

/**
 * 提交信息生成的上下文模型（纯数据，无 IDE 依赖）。
 *
 * 采集层负责从 VCS 取出原始数据填进这里，限长与 prompt 组装是纯函数（可单测）。
 * 字段刻意保持"已限长"的语义：进入本模型的 diff 都已过 [CommitMessageDiffLimiter]。
 */
data class CommitMessageContext(
    /** 当前分支名（未知为 null） */
    val branch: String?,
    /** 最近若干条提交信息（仅供模型学语气与格式，无则空） */
    val recentCommitMessages: List<String>,
    /** 变更文件列表（已限长） */
    val changes: List<CommitMessageChange>,
    /** 总 diff 行数（原始值，用于 prompt 里如实说明被截断了多少） */
    val totalDiffLinesBeforeLimit: Int = 0
)

/**
 * 单个文件的变更。
 *
 * @param path 相对路径
 * @param changeType 变更类型展示文案（如 `M` / `A` / `D`）
 * @param addedLines 新增行数
 * @param removedLines 删除行数
 * @param diff diff 文本；**可能已截断**（`diffTruncated` 为 true），也可能是 null（改动过大只给 stat）
 * @param diffTruncated diff 是否被截断
 * @param statOnly 是否因改动过大而只保留统计、不含 diff
 */
data class CommitMessageChange(
    val path: String,
    val changeType: String,
    val addedLines: Int = 0,
    val removedLines: Int = 0,
    val diff: String? = null,
    val diffTruncated: Boolean = false,
    val statOnly: Boolean = false
)
package com.ayongw.idea.opencode.frontend.vcs

/**
 * diff 限长（纯函数）。
 *
 * 为什么要分级而不是一刀切：
 * - 一刀切截断会让模型看到"半截 diff"，误判改动范围，生成出与实际不符的提交信息；
 * - 不截断则几百行 `ls -la` / 技能文档输出会直接把 prompt 撑爆（token 爆炸 + 极慢）。
 *
 * 分级策略（见 TSD-33 §4.2）：
 * 1. 单文件 diff 行数 > [statOnlyLineThreshold] → **只给统计**（路径 + +/- 行数），不给 diff
 * 2. 否则单文件截到 [maxLinesPerFile]，标注已截断
 * 3. 全局总量截到 [maxTotalLines]，被挤掉的文件标注 `statOnly`（只给统计）
 *
 * 全部为纯计算，不依赖 VCS / Swing，便于单测覆盖边界。
 */
object CommitMessageDiffLimiter {

    /** 单文件超过此行数则只给统计，不给 diff */
    const val statOnlyLineThreshold = 500

    /** 单文件 diff 最多保留行数 */
    const val maxLinesPerFile = 120

    /** 全部文件 diff 合计最多保留行数 */
    const val maxTotalLines = 800

    /**
     * 对原始变更列表限长。
     *
     * @param changes 采集到的原始变更（diff 可为完整文本）
     * @return 限长后的变更列表；顺序保持不变（与采集顺序一致，便于人工核对）
     */
    fun limit(changes: List<CommitMessageChange>): List<CommitMessageChange> {
        var remaining = maxTotalLines
        return changes.map { change ->
            val lines = change.diff?.lines()?.size ?: 0

            // 1) 改动过大：只给统计
            when {
                lines > statOnlyLineThreshold ->
                    // 1) 改动过大：只给统计
                    change.copy(diff = null, diffTruncated = false, statOnly = true)

                lines == 0 ->
                    // 无 diff 可给（原文本为空）
                    change.copy(statOnly = false, diffTruncated = false)

                lines > maxLinesPerFile -> {
                    // 2) 单文件超上限：截到上限并标注（与全局额度无关）
                    remaining = (remaining - maxLinesPerFile).coerceAtLeast(0)
                    change.copy(
                        diff = change.diff!!.lines().take(maxLinesPerFile).joinToString("\n"),
                        diffTruncated = true,
                        statOnly = false
                    )
                }

                lines <= remaining -> {
                    // 3) 额度充足：原样保留
                    remaining -= lines
                    change.copy(statOnly = false, diffTruncated = false)
                }

                remaining > 0 -> {
                    // 4) 额度部分够：截断并标注
                    val kept = change.diff!!.lines().take(remaining)
                    remaining = 0
                    change.copy(diff = kept.joinToString("\n"), diffTruncated = true, statOnly = false)
                }

                else ->
                    // 5) 额度已用尽：退化为只给统计
                    change.copy(diff = null, diffTruncated = false, statOnly = true)
            }
        }
    }
}
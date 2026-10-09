package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.vcs.CommitMessageChange
import com.ayongw.idea.opencode.frontend.vcs.CommitMessageContext
import com.ayongw.idea.opencode.frontend.vcs.CommitMessageDiffLimiter
import com.ayongw.idea.opencode.frontend.vcs.CommitMessagePromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2：上下文限长与 prompt 组装（纯逻辑，无 VCS / IO）。
 *
 * 覆盖 TSD-33 §9.1 的前两组用例。限长是"防 prompt 爆炸"的关键闸门，
 * 且分级策略（只给统计 / 截断标注）直接影响模型对改动范围的判断，必须锁死。
 */
class CommitMessageContextUnitTest {

    private fun change(
        path: String,
        lines: Int,
        changeType: String = "M",
        prefix: String = " "
    ) = CommitMessageChange(
        path = path,
        changeType = changeType,
        addedLines = lines / 2,
        removedLines = lines / 2,
        diff = (1..lines).joinToString("\n") { "$prefix$it" }
    )

    // ==================== 限长：分级策略 ====================

    @Test
    fun 小文件原样保留() {
        val out = CommitMessageDiffLimiter.limit(listOf(change("a.kt", 30)))
        assertEquals("30 行远低于上限，应原样保留", 30, out[0].diff!!.lines().size)
        assertFalse("不应标记截断", out[0].diffTruncated)
        assertFalse("不应退化为只给统计", out[0].statOnly)
    }

    @Test
    fun 单文件超过五百行只给统计() {
        val out = CommitMessageDiffLimiter.limit(listOf(change("big.kt", 501)))
        assertNull("改动过大时不应再给 diff 正文", out[0].diff)
        assertTrue("应标记为只给统计", out[0].statOnly)
        assertFalse("退化为统计不算'截断'", out[0].diffTruncated)
    }

    @Test
    fun 单文件正好五百行仍给diff() {
        // 边界：>500 才只给统计，=500 仍给 diff
        val out = CommitMessageDiffLimiter.limit(listOf(change("edge.kt", 500)))
        assertNotNull("500 行应仍给 diff", out[0].diff)
        assertFalse(out[0].statOnly)
    }

    @Test
    fun 单文件超单文件上限时截断并标注() {
        val out = CommitMessageDiffLimiter.limit(listOf(change("mid.kt", 200)))
        assertEquals("应截到单文件上限", CommitMessageDiffLimiter.maxLinesPerFile, out[0].diff!!.lines().size)
        assertTrue("必须标注已截断", out[0].diffTruncated)
        assertFalse("截断不算只给统计", out[0].statOnly)
    }

    @Test
    fun 全局额度用尽后其余文件退化为统计() {
        val files = (1..12).map { change("f$it.kt", 100) } // 12 × 100 = 1200 > 800
        val out = CommitMessageDiffLimiter.limit(files)

        val withDiff = out.filter { !it.diff.isNullOrBlank() }
        val statOnly = out.filter { it.statOnly }
        assertTrue("应有部分文件保留 diff", withDiff.isNotEmpty())
        assertTrue("超出额度的文件应退化为只给统计", statOnly.isNotEmpty())
        // 总量不得超过上限
        val keptLines = withDiff.sumOf { it.diff!!.lines().size }
        assertTrue("保留的 diff 总量 $keptLines 不应超过 ${CommitMessageDiffLimiter.maxTotalLines}",
            keptLines <= CommitMessageDiffLimiter.maxTotalLines)
    }

    @Test
    fun 顺序保持不变便于人工核对() {
        val files = (1..5).map { change("f$it.kt", 10) }
        val out = CommitMessageDiffLimiter.limit(files)
        assertEquals(files.map { it.path }, out.map { it.path })
    }

    @Test
    fun 空列表与空diff不炸() {
        assertTrue(CommitMessageDiffLimiter.limit(emptyList()).isEmpty())
        val noDiff = CommitMessageChange(path = "x.kt", changeType = "M")
        val out = CommitMessageDiffLimiter.limit(listOf(noDiff))
        assertNull(out[0].diff)
        assertFalse(out[0].statOnly)
    }

    // ==================== prompt 组装 ====================

    private fun context(
        changes: List<CommitMessageChange>,
        branch: String? = "feat/xxx",
        recent: List<String> = emptyList(),
        totalBefore: Int = 0
    ) = CommitMessageContext(
        branch = branch,
        recentCommitMessages = recent,
        changes = changes,
        totalDiffLinesBeforeLimit = totalBefore
    )

    @Test
    fun prompt包含格式约束与语言要求() {
        val p = CommitMessagePromptBuilder.build(context(listOf(change("a.kt", 5))))

        assertTrue("应要求 Conventional Commits", p.contains("Conventional Commits"))
        assertTrue("中文模式应要求中文正文", p.contains("中文"))
        assertTrue("应禁止代码块围栏", p.contains("不要加代码块围栏"))
    }

    @Test
    fun prompt包含分支与最近提交() {
        val p = CommitMessagePromptBuilder.build(
            context(listOf(change("a.kt", 5)), branch = "feat/login", recent = listOf("fix: 修登录跳转"))
        )
        assertTrue("应含分支", p.contains("feat/login"))
        assertTrue("应含最近提交", p.contains("fix: 修登录跳转"))
    }

    @Test
    fun 分支与最近提交缺失时自动省略() {
        val p = CommitMessagePromptBuilder.build(context(listOf(change("a.kt", 5)), branch = null))
        assertFalse("无分支不应输出分支段", p.contains("当前分支"))
        assertFalse("无最近提交不应输出该段", p.contains("最近的提交"))
        assertTrue("但仍应正常生成 prompt", p.contains("Conventional Commits"))
    }

    @Test
    fun 变更文件列表全量输出含增删行数() {
        val changes = listOf(change("a.kt", 10), change("b.kt", 20, changeType = "A"))
        val p = CommitMessagePromptBuilder.build(context(changes))

        assertTrue("应含文件数", p.contains("变更文件（2 个）"))
        assertTrue("应含路径 a.kt", p.contains("a.kt"))
        assertTrue("应含路径 b.kt", p.contains("b.kt"))
        assertTrue("应含增删行数", p.contains("+5 -5"))
    }

    @Test
    fun diff标注为节选避免模型误判全貌() {
        // 先过限长器（真实链路：collect → limit → build），200 行会被截到单文件上限
        val limited = CommitMessageDiffLimiter.limit(listOf(change("a.kt", 200)))
        val p = CommitMessagePromptBuilder.build(context(limited, totalBefore = 200))
        assertTrue("应声明 diff 为节选", p.contains("仅为节选"))
        assertTrue("截断文件应有标注", p.contains("diff 已截断"))
        assertTrue("应如实说明原始总行数", p.contains("原始 diff 合计 200 行"))
    }

    @Test
    fun 全部退化为统计时如实说明() {
        val onlyStat = listOf(
            CommitMessageDiffLimiter.limit(listOf(change("big.kt", 900)))[0]
        )
        val p = CommitMessagePromptBuilder.build(context(onlyStat))

        assertTrue("应说明未提供 diff 正文", p.contains("未提供 diff 正文"))
        assertTrue("应说明仅提供行数统计", p.contains("仅提供行数统计"))
    }

    @Test
    fun 英文模式切换正文语言要求() {
        val p = CommitMessagePromptBuilder.build(
            context(listOf(change("a.kt", 5))),
            language = CommitMessagePromptBuilder.Language.ENGLISH
        )
        assertTrue("英文模式应要求 English", p.contains("正文使用English"))
    }
}
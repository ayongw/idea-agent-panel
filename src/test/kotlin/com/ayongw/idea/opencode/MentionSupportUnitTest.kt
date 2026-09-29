package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 输入框 mention 的触发判定、定位与发送前解析（纯函数）。
 */
class MentionSupportUnitTest {

    private val basePath = "/work/project"

    private fun fileAttachment(path: String, name: String) = ContextFileDto(
        path = path,
        name = name,
        summary = "",
        addedAt = LocalDateTime.now(),
        isExplicit = true,
        kind = ContextKind.FILE
    )

    // ==================== 触发判定 ====================

    @Test
    fun detectsTriggerAtLineStartAndAfterWhitespace() {
        assertEquals('/', MentionSupport.detectTrigger("/init", 5)?.symbol)
        assertEquals('#', MentionSupport.detectTrigger("使用 #Chat", 8)?.symbol)
        assertEquals("Chat", MentionSupport.detectTrigger("使用 #Chat", 8)?.query)
    }

    @Test
    fun ignoresSlashInsideUrl() {
        assertNull("URL 中的 / 不触发", MentionSupport.detectTrigger("https://a.b/c", 11))
    }

    @Test
    fun bareSymbolTriggersWithEmptyQuery() {
        val trigger = MentionSupport.detectTrigger("/", 1)

        assertEquals('/', trigger?.symbol)
        assertEquals("", trigger?.query)
    }

    // ==================== mention 定位 ====================

    @Test
    fun scansSpansWithOffsets() {
        val text = "使用 /sprint-audit 审核 #src/a.kt"
        val spans = MentionSupport.spans(text)

        assertEquals(2, spans.size)
        assertEquals(MentionSupport.Span('/', "sprint-audit", 3, 16), spans[0])
        assertEquals(MentionSupport.Span('#', "src/a.kt", 20, 29), spans[1])
    }

    @Test
    fun ignoresMidWordSymbols() {
        val spans = MentionSupport.spans("a/b#c")
        assertTrue("词中符号不视为 mention", spans.isEmpty())
    }

    // ==================== 发送前解析 ====================

    @Test
    fun resolvesCommandAndStripsMention() {
        val candidate = MentionSupport.Candidate(
            symbol = MentionSupport.COMMAND_SYMBOL,
            token = "sprint-audit",
            group = "Commands",
            label = "/sprint-audit",
            command = true
        )

        val resolution = MentionSupport.resolve("使用 /sprint-audit 审核需求", listOf(candidate), basePath)

        assertEquals("sprint-audit", resolution.commandName)
        assertEquals("使用 审核需求", resolution.text)
        assertTrue(resolution.attachments.isEmpty())
    }

    @Test
    fun resolvesSkillCandidateToSkillAttachment() {
        val candidate = MentionSupport.Candidate(
            symbol = MentionSupport.COMMAND_SYMBOL,
            token = "代码审查",
            group = "Skills",
            label = "代码审查",
            attachment = ContextFileDto(
                path = "/skills/review",
                name = "代码审查",
                summary = "",
                addedAt = LocalDateTime.now(),
                isExplicit = true,
                kind = ContextKind.SKILL,
                skillId = "skill_review"
            )
        )

        val resolution = MentionSupport.resolve("/代码审查 看下这段", listOf(candidate), basePath)

        assertNull(resolution.commandName)
        assertEquals("看下这段", resolution.text)
        assertEquals(1, resolution.attachments.size)
        assertEquals(ContextKind.SKILL, resolution.attachments[0].kind)
        assertEquals("skill_review", resolution.attachments[0].skillId)
    }

    @Test
    fun resolvesRuleCandidateToRuleAttachment() {
        val candidate = MentionSupport.Candidate(
            symbol = MentionSupport.PATH_SYMBOL,
            token = "AGENTS.md",
            group = "Rules",
            label = "AGENTS.md",
            attachment = ContextFileDto(
                path = "/home/me/.config/opencode/AGENTS.md",
                name = "AGENTS.md",
                summary = "",
                addedAt = LocalDateTime.now(),
                isExplicit = true,
                kind = ContextKind.RULE
            )
        )

        val resolution = MentionSupport.resolve("#AGENTS.md 遵守规范", listOf(candidate), basePath)

        assertEquals(1, resolution.attachments.size)
        assertEquals(ContextKind.RULE, resolution.attachments[0].kind)
        assertEquals("/home/me/.config/opencode/AGENTS.md", resolution.attachments[0].path)
    }

    @Test
    fun fallsBackToWorkspaceRelativePath() {
        val resolution = MentionSupport.resolve("看 #src/main/ChatList.kt 的实现", emptyList(), basePath)

        assertEquals(1, resolution.attachments.size)
        val attachment = resolution.attachments[0]
        assertEquals(ContextKind.FILE, attachment.kind)
        assertEquals("/work/project/src/main/ChatList.kt", attachment.path)
        assertEquals("ChatList.kt", attachment.name)
    }

    @Test
    fun treatsTrailingSlashAsDirectory() {
        val resolution = MentionSupport.resolve("#src/main/ 目录里的文件", emptyList(), basePath)

        val attachment = resolution.attachments.single()
        assertEquals(ContextKind.DIRECTORY, attachment.kind)
        assertEquals("/work/project/src/main", attachment.path)
        assertEquals(MentionSupport.DIRECTORY_SUMMARY, attachment.summary)
    }

    @Test
    fun keepsUnresolvedCommandAsPlainText() {
        val resolution = MentionSupport.resolve("用 /unknown-cmd 试试", emptyList(), basePath)

        assertNull(resolution.commandName)
        assertEquals("用 /unknown-cmd 试试", resolution.text)
        assertTrue(resolution.attachments.isEmpty())
    }

    @Test
    fun deduplicatesSameAttachment() {
        val resolution = MentionSupport.resolve("#a.kt 与 #a.kt 都看看", emptyList(), basePath)

        assertEquals(1, resolution.attachments.size)
    }

    @Test
    fun candidateTakesPrecedenceOverPathFallback() {
        val candidate = MentionSupport.Candidate(
            symbol = MentionSupport.PATH_SYMBOL,
            token = "a.kt",
            group = "Files & folders",
            label = "a.kt",
            attachment = fileAttachment("/other/place/a.kt", "a.kt")
        )

        val resolution = MentionSupport.resolve("#a.kt 看一下", listOf(candidate), basePath)

        assertEquals("/other/place/a.kt", resolution.attachments.single().path)
    }
}
package com.ayongw.idea.opencode.frontend.chatApp.ui.utils

import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextKind
import java.time.LocalDateTime

/**
 * 输入框 mention（`/` 命令与技能、`#` 文件与目录）的定位与解析。
 *
 * 全部为纯函数，便于单测；文本是 mention 的唯一真源：
 * - 输入过程中用 [detectTrigger] 决定是否弹候选；
 * - 渲染 chips 时用 [spans] 取全部 mention；
 * - 发送前用 [resolve] 把 mention 还原为结构化附件与命令，并得到剔除 mention 的文本。
 */
object MentionSupport {

    /** 命令与技能共用的触发符 */
    const val COMMAND_SYMBOL = '/'

    /** 文件、目录与规则共用的触发符 */
    const val PATH_SYMBOL = '#'

    /** 正在输入的 mention（触发符之后、光标之前的连续非空白字串） */
    data class Trigger(val symbol: Char, val start: Int, val query: String)

    /** 文本中一处 mention：`[start, end)` 覆盖触发符与其后的 token */
    data class Span(val symbol: Char, val token: String, val start: Int, val end: Int)

    /**
     * 光标是否处于 mention 触发态。
     *
     * 触发符必须位于行首或空白之后（避免 URL / 路径中的 `/`、`#` 误触发）。
     */
    fun detectTrigger(text: String, caret: Int): Trigger? {
        if (caret <= 0 || caret > text.length) return null
        var index = caret - 1
        while (index >= 0 && !text[index].isWhitespace() && !isSymbol(text[index])) index--
        if (index < 0 || !isSymbol(text[index])) return null
        if (index > 0 && !text[index - 1].isWhitespace()) return null
        return Trigger(text[index], index, text.substring(index + 1, caret))
    }

    /** 扫描文本中的全部 mention（按出现顺序） */
    fun spans(text: String): List<Span> {
        val result = mutableListOf<Span>()
        var index = 0
        while (index < text.length) {
            val symbol = text[index]
            if (isSymbol(symbol) && (index == 0 || text[index - 1].isWhitespace())) {
                var end = index + 1
                while (end < text.length && !text[end].isWhitespace()) end++
                if (end > index + 1) {
                    result += Span(symbol, text.substring(index + 1, end), index, end)
                }
                index = end
            } else {
                index++
            }
        }
        return result
    }

    /** 候选条目：由弹窗展示并决定 mention 的插入文本 */
    data class Candidate(
        val symbol: Char,
        /** 插入到文本中的名字或相对路径 */
        val token: String,
        /** 分组标题（命令 / 技能 / 规则 / 文件目录） */
        val group: String,
        /** 主文本 */
        val label: String,
        /** 次文本（描述或路径） */
        val detail: String? = null,
        /** 对应的上下文附件；命令没有附件（发送时走 command 端点） */
        val attachment: ContextFileDto? = null,
        /** 命令条目（发送时作为 `commandName`） */
        val command: Boolean = false
    )

    /** 解析结果：剔除 mention 后的文本 + 结构化上下文 */
    data class Resolution(
        val text: String,
        val attachments: List<ContextFileDto>,
        val commandName: String?
    )

    /**
     * 发送前解析增量。
     *
     * 判定顺序：候选表精确命中（命令 > 技能 > 规则 > 文件目录）→ 未命中的 `#token` 按工作区相对路径兜底；
     * 未命中的 `/token` 视为普通文本（保留，不产生附件）。
     */
    fun resolve(
        text: String,
        candidates: List<Candidate>,
        basePath: String?
    ): Resolution {
        val byToken = candidates
            .filter { it.symbol == PATH_SYMBOL || it.symbol == COMMAND_SYMBOL }
            .associateBy { it.symbol to it.token }

        val attachments = mutableListOf<ContextFileDto>()
        var commandName: String? = null
        val kept = StringBuilder()
        var cursor = 0

        for (span in spans(text)) {
            val hit = byToken[span.symbol to span.token]
            val consumed = when {
                hit?.command == true -> {
                    if (commandName == null) commandName = hit.token
                    commandName == hit.token
                }
                hit?.attachment != null -> {
                    attachments += hit.attachment
                    true
                }
                span.symbol == PATH_SYMBOL -> {
                    pathAttachment(span.token, basePath)?.let { attachments += it }
                    true
                }
                else -> false
            }
            if (!consumed) continue
            kept.append(text, cursor, span.start)
            // 连同 mention 后的一个空格一起剔除，避免发送文本出现双空格
            cursor = if (span.end < text.length && text[span.end] == ' ') span.end + 1 else span.end
        }
        kept.append(text, cursor, text.length)

        return Resolution(
            text = kept.toString().trim(),
            attachments = attachments.distinctBy { it.kind to it.path },
            commandName = commandName
        )
    }

    /** 未命中候选表的 `#token` 按工作区相对路径兜底（以 `/` 结尾视为目录） */
    private fun pathAttachment(token: String, basePath: String?): ContextFileDto? {
        val relative = token.trimEnd('/')
        if (relative.isBlank()) return null
        val absolute = when {
            relative.startsWith("/") -> relative
            basePath.isNullOrBlank() -> return null
            else -> "$basePath/${relative.trimStart('/')}"
        }
        val isDirectory = token.endsWith("/")
        val name = relative.substringAfterLast('/').ifBlank { relative }
        return ContextFileDto(
            path = absolute,
            name = name,
            summary = if (isDirectory) DIRECTORY_SUMMARY else "",
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = if (isDirectory) ContextKind.DIRECTORY else ContextKind.FILE,
            skillId = null
        )
    }

    /** 目录附件在 `summary` 上的标记 */
    const val DIRECTORY_SUMMARY = "directory"

    private fun isSymbol(char: Char): Boolean = char == COMMAND_SYMBOL || char == PATH_SYMBOL
}
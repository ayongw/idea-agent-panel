package com.ayongw.idea.agentpanel.frontend.chatApp.ui.md

/**
 * Markdown 分段（TSD-30 §5.8）：气泡内容按「文本段 / 代码块段」切分后分别交给渲染器。
 */
internal sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment()
}

internal fun parseMarkdownWithCodeBlocks(content: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = content.lines().toList()
    var i = 0
    var textBuffer = StringBuilder()

    while (i < lines.size) {
        val line = lines[i]
        if (line.trim().startsWith("```")) {
            // Flush pending text
            if (textBuffer.isNotEmpty()) {
                segments.add(MarkdownSegment.Text(textBuffer.toString()))
                textBuffer = StringBuilder()
            }

            val fence = line.trim()
            val language = fence.substring(3).trim().takeIf { it.isNotBlank() } ?: "plaintext"
            i++
            val codeLines = mutableListOf<String>()
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            // Skip closing fence
            if (i < lines.size) i++
            segments.add(MarkdownSegment.CodeBlock(language, codeLines.joinToString("\n")))
        } else {
            textBuffer.append(line).append("\n")
            i++
        }
    }

    if (textBuffer.isNotEmpty()) {
        segments.add(MarkdownSegment.Text(textBuffer.toString()))
    }

    return segments
}

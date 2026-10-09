package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * JSONC 定点编辑器：只改目标键的值区间，其余字符（注释、缩进、未知字段）原样保留。
 *
 * 语义对齐 opencode 官方所用的 jsonc-parser `modify + applyEdits`
 * （见 opencode 仓库 `packages/cli/src/commands/handlers/mcp/add.ts`）。
 * 路径中缺失的中间层级会自动补成空对象。
 */
object JsoncEditor {

    private val pretty: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val plain: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * 按 JSON 路径写入/删除键。
     *
     * @param text 原始 JSONC 文本（空白视为 `{}`）
     * @param path JSON 路径，如 `["mcp","servers","codegraph"]`
     * @param value 新值；为 null 表示删除该键
     */
    fun patch(text: String, path: List<String>, value: JsonElement?): String {
        require(path.isNotEmpty()) { "path 不能为空" }
        val base = text.ifBlank { "{}\n" }
        val root = skipTrivia(base, 0)
        require(root < base.length && base[root] == '{') { "配置根节点必须是对象" }
        return patchObject(base, root, path, value)
    }

    // ==================== 递归写入 ====================

    private fun patchObject(text: String, brace: Int, path: List<String>, value: JsonElement?): String {
        val end = matchPair(text, brace, '{', '}')
        val members = parseMembers(text, brace, end)
        val key = path.first()
        val member = members.firstOrNull { it.key == key }

        if (path.size == 1) {
            return when {
                value == null -> if (member == null) text else deleteMember(text, brace, members, member)
                member == null -> insertMember(text, brace, end, members, key, value)
                else -> replaceValue(text, brace, members.first(), member, value)
            }
        }

        // 多级路径
        if (member == null) {
            if (value == null) return text
            return insertMember(text, brace, end, members, key, wrap(path.drop(1), value))
        }
        val valueBrace = skipTrivia(text, member.valueStart)
        if (valueBrace >= text.length || text[valueBrace] != '{') {
            if (value == null) return deleteMember(text, brace, members, member)
            return replaceValue(text, brace, members.first(), member, wrap(path.drop(1), value))
        }
        return patchObject(text, valueBrace, path.drop(1), value)
    }

    /** 把 value 包成嵌套对象，如 (["a","b"], v) -> {a:{b:v}} */
    private fun wrap(path: List<String>, value: JsonElement): JsonElement {
        var current: JsonElement = value
        for (key in path.asReversed()) {
            val obj = JsonObject()
            obj.add(key, current)
            current = obj
        }
        return current
    }

    // ==================== 成员操作 ====================

    /** 成员在容器内的位置信息 */
    private class Member(val key: String, val keyStart: Int, val valueStart: Int, val valueEnd: Int)

    private fun replaceValue(
        text: String,
        brace: Int,
        firstMember: Member,
        member: Member,
        value: JsonElement
    ): String {
        val indent = lineIndent(text, firstMember.keyStart)
        val rendered = render(value, indent)
        return text.substring(0, member.valueStart) + rendered + text.substring(member.valueEnd)
    }

    private fun insertMember(
        text: String,
        brace: Int,
        end: Int,
        members: List<Member>,
        key: String,
        value: JsonElement
    ): String {
        val objIndent = lineIndent(text, brace)
        val keyLiteral = plain.toJson(key)
        if (members.isEmpty()) {
            val childIndent = objIndent + "  "
            val rendered = render(value, childIndent)
            val inner = "\n" + childIndent + keyLiteral + ": " + rendered + "\n" + objIndent
            // 用新内容替换 [brace+1, end) 之间的原有空白
            return text.substring(0, brace + 1) + inner + text.substring(end)
        }
        val childIndent = lineIndent(text, members.first().keyStart).ifEmpty { objIndent + "  " }
        val rendered = render(value, childIndent)
        val insertAt = members.last().valueEnd
        return text.substring(0, insertAt) + ",\n" + childIndent + keyLiteral + ": " + rendered +
            text.substring(insertAt)
    }

    private fun deleteMember(text: String, brace: Int, members: List<Member>, member: Member): String {
        val index = members.indexOf(member)
        val keyLineStart = lineStart(text, member.keyStart)
        val nextNewline = text.indexOf('\n', member.valueEnd)
        val lineEnd = if (nextNewline >= 0) nextNewline + 1 else member.valueEnd

        // 整行只含该成员时按整行删除（保留其它行的格式），否则退化为行内删除
        val memberText = text.substring(member.keyStart, member.valueEnd)
        val leftover = text.substring(keyLineStart, lineEnd)
            .replace(memberText, "")
            .filterNot { it.isWhitespace() || it == ',' }
        val lineBased = keyLineStart > brace && leftover.isEmpty()

        val edits = mutableListOf<IntRange>()
        if (lineBased) {
            edits += keyLineStart until lineEnd
            if (index == members.lastIndex && index > 0) {
                // 删的是最后一个成员：去掉前一个成员值后的逗号
                var k = members[index - 1].valueEnd
                while (k < text.length && text[k].isWhitespace()) k++
                if (k < text.length && text[k] == ',') edits += k until (k + 1)
            }
        } else {
            edits += member.keyStart until member.valueEnd
            var k = skipTrivia(text, member.valueEnd)
            if (k < text.length && text[k] == ',') {
                edits += k until (k + 1)
            } else {
                var p = member.keyStart - 1
                while (p >= 0 && text[p].isWhitespace()) p--
                if (p >= 0 && text[p] == ',') edits += p until (p + 1)
            }
        }

        var result = text
        edits.sortedByDescending { it.first }.forEach { range ->
            result = result.substring(0, range.first) + result.substring(range.last + 1)
        }
        return result
    }

    // ==================== 扫描原语 ====================

    private fun parseMembers(text: String, brace: Int, end: Int): List<Member> {
        val members = mutableListOf<Member>()
        var i = skipTrivia(text, brace + 1)
        while (i < end && text[i] == '"') {
            val keyEnd = endOfString(text, i)
            if (keyEnd > text.length) break
            val key = unescape(text.substring(i + 1, keyEnd - 1))
            var colon = skipTrivia(text, keyEnd)
            if (colon >= text.length || text[colon] != ':') break
            val valueStart = skipTrivia(text, colon + 1)
            if (valueStart >= text.length) break
            val valueEnd = endOfValue(text, valueStart)
            members += Member(key, i, valueStart, valueEnd)
            val next = skipTrivia(text, valueEnd)
            if (next < text.length && text[next] == ',') {
                i = skipTrivia(text, next + 1)
            } else {
                break
            }
        }
        return members
    }

    /** 跳过空白与注释 */
    private fun skipTrivia(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                }
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> return i
            }
        }
        return i
    }

    /** 从字符串起始引号返回结束引号之后的位置 */
    private fun endOfString(text: String, quote: Int): Int {
        var i = quote + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    /** 从值起点返回值结束（不含）的位置 */
    private fun endOfValue(text: String, start: Int): Int {
        return when (text[start]) {
            '"' -> endOfString(text, start)
            '{' -> matchPair(text, start, '{', '}') + 1
            '[' -> matchPair(text, start, '[', ']') + 1
            else -> {
                var i = start
                while (i < text.length && text[i] !in ",}]" && text[i] != '/') i++
                var end = i
                while (end > start && text[end - 1].isWhitespace()) end--
                end
            }
        }
    }

    /** 返回配对括号的位置（跳过字符串与注释） */
    private fun matchPair(text: String, open: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text[i] == '"' -> i = endOfString(text, i)
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                }
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                text[i] == openChar -> {
                    depth++
                    i++
                }
                text[i] == closeChar -> {
                    depth--
                    i++
                    if (depth == 0) return i - 1
                }
                else -> i++
            }
        }
        throw IllegalArgumentException("括号不匹配：位置 $open")
    }

    /** 所在行的缩进 */
    private fun lineIndent(text: String, index: Int): String {
        val start = lineStart(text, index)
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(start, i)
    }

    private fun lineStart(text: String, index: Int): Int {
        if (index <= 0) return 0
        val newline = text.lastIndexOf('\n', index - 1)
        return if (newline < 0) 0 else newline + 1
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            sb.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 格式化值；indent 为空（压缩文件）时输出单行 */
    private fun render(value: JsonElement, indent: String): String {
        val formatted = pretty.toJson(value)
        if (indent.isEmpty() || !formatted.contains('\n')) return formatted
        val lines = formatted.split('\n')
        return lines.first() + lines.drop(1).joinToString("") { "\n$indent$it" }
    }
}
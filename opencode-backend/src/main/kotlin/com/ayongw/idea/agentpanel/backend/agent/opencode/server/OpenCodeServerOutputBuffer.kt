package com.ayongw.idea.agentpanel.backend.agent.opencode.server

/**
 * 子进程输出有界缓冲（TSD-31 §3.6 / §4.4）
 *
 * - 内存环形缓冲，**不落盘**；行数与单行长度都有上限，避免大输出吃内存或写出超长日志；
 * - 写入时逐行脱敏：已登记的密钥原文、`Authorization: Basic xxx`、`password=xxx` 形态均被替换为 `***`；
 * - 线程安全（进程输出回调与 UI 读取并发）。
 */
class OpenCodeServerOutputBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH,
) {

    private val lines = ArrayDeque<String>()
    private val secrets = LinkedHashSet<String>()

    val size: Int
        @Synchronized get() = lines.size

    /** 登记需要脱敏的密钥（如自有实例密码）；空白值忽略 */
    @Synchronized
    fun registerSecret(secret: String?) {
        secret?.trim()?.takeIf { it.isNotEmpty() }?.let { secrets += it }
    }

    /** 追加一段输出（可含换行，按行拆分后逐行存入） */
    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        text.split('\n').forEach { raw ->
            appendLineInternal(raw.removeSuffix("\r"))
        }
    }

    /** 追加整行输出 */
    @Synchronized
    fun appendLine(line: String) {
        appendLineInternal(line.removeSuffix("\r"))
    }

    /** 全量快照（已脱敏） */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** 取末尾 [limit] 行（已脱敏），用于 FAILED 时展示输出尾巴 */
    @Synchronized
    fun snapshotTail(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        return lines.toList().takeLast(limit)
    }

    @Synchronized
    fun clear() = lines.clear()

    private fun appendLineInternal(line: String) {
        val sanitized = redact(line, secrets)
        val truncated = if (sanitized.length > maxLineLength) {
            sanitized.take(maxLineLength) + TRUNCATED_MARK
        } else {
            sanitized
        }
        lines.addLast(truncated)
        while (lines.size > maxLines) lines.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_LINES = 200
        const val DEFAULT_MAX_LINE_LENGTH = 4_096
        const val MASK = "***"
        private const val TRUNCATED_MARK = "...[truncated]"

        private val BASIC_HEADER = Regex("(?i)(authorization\\s*:\\s*basic\\s+)\\S+")
        private val PASSWORD_PAIR = Regex("(?i)(\"?password\"?\\s*[:=]\\s*)(\"?)([^\"'\\s,}\\]]+)")

        /** 对单行脱敏：先替换登记的密钥原文，再按常见凭据格式兜底 */
        fun redact(line: String, secrets: Collection<String>): String {
            var result = line
            secrets.forEach { secret ->
                if (secret.isNotEmpty()) result = result.replace(secret, MASK)
            }
            result = BASIC_HEADER.replace(result) { "${it.groupValues[1]}$MASK" }
            result = PASSWORD_PAIR.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            return result
        }
    }
}
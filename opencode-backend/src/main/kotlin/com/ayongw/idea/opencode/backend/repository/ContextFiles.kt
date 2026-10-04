package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.nio.file.Paths

/**
 * 会话上下文附件（TSD-30 §5.8）：会话级附件的增删查 + 发送前的 prompt 附件组装。
 *
 * 从 BackendChatRepositoryModel 抽出；附件状态按 sessionId 分桶（命令为一次性，发送成功后清除），
 * 组装时合并本次 mention 解析附件并按 `kind + path` 去重。
 */
internal class ContextFiles {

    /** 发送时下发给 REST 的文件附件 + 技能 id */
    data class PromptAttachments(
        val files: List<PromptFile>,
        val skills: List<String>,
    )

    private val state = MutableStateFlow<Map<String, List<ContextFileDto>>>(emptyMap())

    /** 指定会话的上下文附件流 */
    fun flowOf(sessionId: String): Flow<List<ContextFileDto>> =
        state.map { it[sessionId].orEmpty() }

    /** 添加附件：按 `kind + path` 去重（保留先添加者） */
    fun add(sessionId: String, contextFile: ContextFileDto) {
        val current = of(sessionId)
        if (current.any { it.kind == contextFile.kind && it.path == contextFile.path }) return
        state.value = state.value + (sessionId to (current + contextFile))
    }

    /** 移除指定路径的附件 */
    fun remove(sessionId: String, path: String) {
        val current = of(sessionId)
        if (current.none { it.path == path }) return
        state.value = state.value + (sessionId to current.filterNot { it.path == path })
    }

    /** 清空指定会话的附件 */
    fun clear(sessionId: String) {
        if (of(sessionId).isEmpty()) return
        state.value = state.value - sessionId
    }

    private fun of(sessionId: String): List<ContextFileDto> =
        state.value[sessionId].orEmpty()

    /** 会话附件（＋）与本次 mention 解析附件合并去重后，拆成 prompt 文件附件与技能 id */
    fun assembleForPrompt(sessionId: String, incoming: List<ContextFileDto>): PromptAttachments {
        val merged = (of(sessionId) + incoming).distinctBy { it.kind to it.path }
        return PromptAttachments(
            files = merged
                .filter {
                    it.kind == ContextKind.FILE ||
                        it.kind == ContextKind.DIRECTORY ||
                        it.kind == ContextKind.RULE
                }
                .map {
                    PromptFile(
                        uri = fileUri(it.path),
                        name = it.name,
                        description = it.summary.takeIf { summary -> summary.isNotBlank() }
                    )
                },
            skills = merged.filter { it.kind == ContextKind.SKILL }.mapNotNull { it.skillId }
        )
    }

    /** 绝对路径 → `file://` uri（自动转义空格与非 ASCII 字符） */
    private fun fileUri(path: String): String =
        if (path.startsWith("file:")) path else Paths.get(path).toUri().toString()
}

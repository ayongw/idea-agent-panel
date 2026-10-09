package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.CommitMessageRequestDto
import com.ayongw.idea.opencode.shared.CommitMessageResultDto
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files

/**
 * 提交信息生成（TSD-33 S3）。
 *
 * 用**一次性会话**跑一次 opencode 对话：建会话 → 发提示 → 取助手回复 → 删会话。
 * 目录落在系统临时目录而非工作区，前端按 `session.directory` 过滤工作区会话，
 * 因此不会污染用户的「全部会话」列表。
 *
 * 模型：只认调用方（前端设置页）传入的模型，**空则直接失败**（见 TSD-33 §5.1.1）——
 * 不自动回退到其它模型，避免用户不知情地被消耗额度。
 */
internal class CommitMessageGenerator(
    private val restClientProvider: () -> OpenCodeRestClient
) {

    private val log = Logger.getInstance(CommitMessageGenerator::class.java)

    suspend fun generate(request: CommitMessageRequestDto): CommitMessageResultDto {
        if (request.providerId.isBlank() || request.modelId.isBlank()) {
            log.info("生成提交信息：未配置模型，直接返回引导提示")
            return CommitMessageResultDto(
                success = false,
                reason = CommitMessageResultDto.REASON_NO_MODEL,
                detail = "请先在设置中指定「提交信息生成模型」"
            )
        }
        if (request.prompt.isBlank()) {
            return CommitMessageResultDto(success = false, reason = CommitMessageResultDto.REASON_UNAVAILABLE, detail = "提示词为空")
        }
        return runOnce(request)
    }

    /** 生成失败也返回结果对象而不是抛异常：前端要把失败原因映射成不同提示 */
    private suspend fun runOnce(request: CommitMessageRequestDto): CommitMessageResultDto = runCatching {
        val restClient = restClientProvider()
        val workDir = Files.createTempDirectory("opencode-commit-msg").toFile()
        var sessionId: String? = null

        try {
            val created = restClient.createSession(
                title = "commit-message",
                directory = workDir.absolutePath
            ).getOrThrow()
            sessionId = created
            log.info("提交信息生成：一次性会话已建 session=$created")

            val result = withTimeoutOrNull(TIMEOUT_MS) {
                restClient.sendPrompt(
                    sessionId = created,
                    text = request.prompt,
                    model = request.providerId to request.modelId
                )
            }

            if (result == null) {
                return@runCatching CommitMessageResultDto(
                    success = false,
                    reason = CommitMessageResultDto.REASON_TIMEOUT,
                    detail = "生成超时（${TIMEOUT_MS / 1000}s）"
                )
            }
            result.getOrNull()
                ?: return@runCatching CommitMessageResultDto(
                    success = false,
                    reason = CommitMessageResultDto.REASON_UNAVAILABLE,
                    detail = "调用失败：${result.exceptionOrNull()?.message}"
                )

            val reply = latestAssistantText(restClient, created)
            if (reply.isBlank()) {
                CommitMessageResultDto(
                    success = false,
                    reason = CommitMessageResultDto.REASON_UNAVAILABLE,
                    detail = "模型未返回文本"
                )
            } else {
                CommitMessageResultDto(success = true, text = reply.trim())
            }
        } catch (e: Exception) {
            log.warn("提交信息生成失败: ${e.message}")
            CommitMessageResultDto(
                success = false,
                reason = CommitMessageResultDto.REASON_UNAVAILABLE,
                detail = e.message ?: e::class.simpleName.orEmpty()
            )
        } finally {
            // 一次性会话用完即删；删除失败只记日志（列表按目录过滤，残留也不会出现在工作区）
            sessionId?.let { sid ->
                runCatching { restClientProvider().deleteSession(sid) }
                    .onFailure { log.warn("一次性会话删除失败 session=$sid: ${it.message}") }
            }
            runCatching { workDir.deleteRecursively() }
        }
    }.getOrElse {
        CommitMessageResultDto(
            success = false,
            reason = CommitMessageResultDto.REASON_UNAVAILABLE,
            detail = it.message ?: "未知错误"
        )
    }

    /** 取最后一条助手消息的正文（REST 消息列表最新在前） */
    private suspend fun latestAssistantText(restClient: OpenCodeRestClient, sessionId: String): String {
        val messages = restClient.getMessages(sessionId).getOrNull().orEmpty()
        return messages.firstOrNull { it.role == "assistant" }?.content.orEmpty()
    }

    private companion object {
        /** 生成超时（opencode 本机调用通常数秒，60s 足够宽松） */
        const val TIMEOUT_MS = 60_000L
    }
}
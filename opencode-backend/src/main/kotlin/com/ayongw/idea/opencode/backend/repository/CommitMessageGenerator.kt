package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.CommitMessageRequestDto
import com.ayongw.idea.opencode.shared.CommitMessageResultDto
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

            // sendPrompt 是「提交即返回」：返回时模型通常还没吐字，此时查消息必然为空。
            // 必须轮询等待首条非空 assistant 正文（见 awaitAssistantText）。
            val reply = withTimeoutOrNull(TIMEOUT_MS) { awaitAssistantText(restClient, created) }
                ?: return@runCatching CommitMessageResultDto(
                    success = false,
                    reason = CommitMessageResultDto.REASON_TIMEOUT,
                    detail = "等待模型输出超时（${TIMEOUT_MS / 1000}s）"
                )
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

    /**
     * 轮询等待首条非空助手正文。
     *
     * 为什么必须轮询：`POST /session/{id}/message` 在模型**开始生成前**就返回，
     * 此刻 `GET .../message` 要么一条都没有，要么最后一条 assistant 的 content 还是空
     * （正文分片落在 `content[]` 的 text 项里，边生成边追加）。
     *
     * 取「最后一条」而非第一条：一次 prompt 可能产生多条 assistant 消息（工具调用轮次），
     * 真正的结论是最后那条。
     */
    private suspend fun awaitAssistantText(restClient: OpenCodeRestClient, sessionId: String): String {
        var attempts = 0
        while (attempts < MAX_POLL_ATTEMPTS) {
            val text = restClient.getMessages(sessionId).getOrNull()
                ?.lastOrNull { it.role == "assistant" }
                ?.content
                .orEmpty()
            if (text.isNotBlank()) {
                log.info("提交信息生成：已取到模型输出（${++attempts} 次轮询，${text.length} 字符）")
                return text
            }
            attempts++
            delay(POLL_INTERVAL_MS)
        }
        log.warn("提交信息生成：轮询 $MAX_POLL_ATTEMPTS 次仍无模型输出")
        return ""
    }

    private companion object {
        /** 生成超时（opencode 本机调用通常数秒，60s 足够宽松） */
        const val TIMEOUT_MS = 60_000L

        /** 轮询间隔 */
        const val POLL_INTERVAL_MS = 700L

        /** 轮询次数上限（与 TIMEOUT_MS 共同兜底） */
        const val MAX_POLL_ATTEMPTS = 85
    }
}
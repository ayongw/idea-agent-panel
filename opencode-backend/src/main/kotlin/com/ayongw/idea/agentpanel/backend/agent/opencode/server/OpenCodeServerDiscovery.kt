package com.ayongw.idea.agentpanel.backend.agent.opencode.server

import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.OpenCodeAuth
import com.google.gson.JsonParser
import java.io.IOException

/** `GET /api/info` 的关键字段：`pid` 用于归属校验，`version` 用于展示与排查 */
data class OpenCodeServerInfo(val pid: Long?, val version: String?)

/** 就绪探测节奏（TSD-31 §3.5 的建议值集中在此，测试可覆盖） */
data class OpenCodeServerProbePolicy(
    val connectTimeoutMs: Int = 800,
    val readTimeoutMs: Int = 1_500,
    val initialRetryDelayMs: Long = 250,
    val maxRetryDelayMs: Long = 2_000,
    val totalTimeoutMs: Long = 20_000,
)

/** 探测结果 */
sealed interface OpenCodeServerProbeResult {
    /** 端点可用（2xx + JSON 服务信息） */
    data class Ready(val info: OpenCodeServerInfo) : OpenCodeServerProbeResult

    /**
     * 端点不可用
     *
     * [retryable] 为 false 表示重试也不会变好（认证失败、端口上跑的不是 opencode 服务），调用方应立刻换策略。
     */
    data class NotReady(
        val failure: OpenCodeServerFailure,
        val detail: String,
        val attempts: Int = 1,
        val retryable: Boolean = true,
    ) : OpenCodeServerProbeResult
}

/**
 * 发现与就绪探测（TSD-31 §3.3 / §3.5）
 *
 * 纯逻辑 + 注入式传输：HTTP 层可替换（单测用本地 `HttpServer` 桩），重试节奏用注入的 sleeper/clock 驱动。
 *
 * 判据要点：
 * - 就绪 = `2xx` 且响应体是 JSON 对象且能取到 `pid`/`version` 之一（防「端口上是别的服务」）；
 * - **不依赖 503**：`opencode` v2.0.18 的 `/api/info` 契约只有 200/400/401（TSD-31 §12 A14）；
 * - 401/403 与「2xx 但非 opencode 服务」立即返回，不做无意义轮询（§3.5 / §4.3）。
 */
open class OpenCodeServerDiscovery(
    private val http: OpenCodeServerHttpProbe = OkHttpProbe(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 单次探测（不带重试） */
    open fun probe(
        endpoint: OpenCodeServerEndpoint,
        policy: OpenCodeServerProbePolicy = OpenCodeServerProbePolicy(),
    ): OpenCodeServerProbeResult {
        val authHeader = OpenCodeAuth.basicHeader(endpoint.username, endpoint.password)
        val response = try {
            http.get(
                endpoint.baseUrl.trimEnd('/') + INFO_PATH,
                authHeader,
                policy.connectTimeoutMs,
                policy.readTimeoutMs,
            )
        } catch (e: IOException) {
            return OpenCodeServerProbeResult.NotReady(
                OpenCodeServerFailure.UNREACHABLE,
                "连接失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
        return classify(response)
    }

    /**
     * 轮询直到就绪或总超时
     *
     * 返回 [OpenCodeServerProbeResult.Ready]、立即可判定的 [OpenCodeServerProbeResult.NotReady]（AUTH_FAILED / 非本服务），
     * 或总超时后的 `READY_TIMEOUT`（进程是否存活由调用方判断，管理器可据此细化为 `PROCESS_EXITED`）。
     */
    open fun awaitReady(
        endpoint: OpenCodeServerEndpoint,
        policy: OpenCodeServerProbePolicy = OpenCodeServerProbePolicy(),
    ): OpenCodeServerProbeResult {
        val deadline = clock() + policy.totalTimeoutMs
        var attempts = 0
        var delay = policy.initialRetryDelayMs
        var lastDetail = "无响应"

        while (true) {
            attempts++
            when (val result = probe(endpoint, policy)) {
                is OpenCodeServerProbeResult.Ready -> return result
                is OpenCodeServerProbeResult.NotReady -> {
                    lastDetail = result.detail
                    if (!result.retryable) return result.copy(attempts = attempts)
                }
            }

            val remaining = deadline - clock()
            if (remaining <= 0) break
            val sleepMs = minOf(delay, remaining)
            if (sleepMs <= 0) break
            sleeper(sleepMs)
            delay = minOf(delay * 2, policy.maxRetryDelayMs)
        }

        return OpenCodeServerProbeResult.NotReady(
            failure = OpenCodeServerFailure.READY_TIMEOUT,
            detail = "等待就绪超时（最后状态：$lastDetail）",
            attempts = attempts,
        )
    }

    /** 按状态码与响应体分类 */
    internal fun classify(response: OpenCodeServerHttpResponse): OpenCodeServerProbeResult = when {
        response.statusCode == 401 || response.statusCode == 403 -> OpenCodeServerProbeResult.NotReady(
            OpenCodeServerFailure.AUTH_FAILED,
            "HTTP ${response.statusCode}",
            retryable = false,
        )

        response.statusCode !in 200..299 -> OpenCodeServerProbeResult.NotReady(
            OpenCodeServerFailure.UNREACHABLE,
            "HTTP ${response.statusCode}",
            retryable = true,
        )

        else -> parseServerInfo(response.body)?.let { OpenCodeServerProbeResult.Ready(it) }
            ?: OpenCodeServerProbeResult.NotReady(
                OpenCodeServerFailure.UNREACHABLE,
                "响应体不是 opencode 服务信息（端口上可能是别的服务）",
                retryable = false,
            )
    }

    companion object {
        const val INFO_PATH = "/api/info"

        /** 解析 `GET /api/info` 响应体；兼容根对象与 `{"data":{...}}` 两种包裹，取不到 `pid`/`version` 视为非 opencode 服务 */
        fun parseServerInfo(body: String): OpenCodeServerInfo? {
            val root = try {
                JsonParser.parseString(body)
            } catch (_: RuntimeException) {
                return null
            }
            if (!root.isJsonObject) return null
            val payload = root.asJsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: root.asJsonObject
            val pid = payload.get("pid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            val version = payload.get("version")?.takeIf { it.isJsonPrimitive }?.asString
            return if (pid == null && version == null) null else OpenCodeServerInfo(pid, version)
        }
    }
}
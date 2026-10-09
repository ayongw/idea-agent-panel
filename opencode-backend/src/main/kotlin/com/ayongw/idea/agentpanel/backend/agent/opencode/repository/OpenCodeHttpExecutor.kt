package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * OpenCode REST HTTP 传输层（自 [OpenCodeRestClient] 拆出）
 *
 * - 统一 HTTP 客户端（TSD-30 §5.9）：REST 与事件流共用 [OpenCodeHttpClientFactory.shared]
 *   基座实例（同一连接池/一份配置）；Basic 认证按请求附加
 * - 响应统一为 `Result` 语义：非 2xx 与 IO 异常均归一为 [OpenCodeResult.Failure]
 */
internal class OpenCodeHttpExecutor(
    baseUrl: String,
    private val username: String,
    private val password: String?
) {

    private val base: String = baseUrl.trim().trimEnd('/')
    private val httpClient: OkHttpClient = OpenCodeHttpClientFactory.shared
    private val log = Logger.getInstance(OpenCodeHttpExecutor::class.java)

    /** Basic 认证头取值，密码为空时返回 null（与事件流客户端共用 [OpenCodeAuth]） */
    private fun authHeaderValue(): String? = OpenCodeAuth.basicHeader(username, password)

    suspend fun <T> execute(
        method: String,
        path: String,
        body: String? = null,
        parse: (String) -> T
    ): OpenCodeResult<T> {
        val url = "$base/api$path"
        return try {
            val response = withContext(Dispatchers.IO) { okHttpRequest(method, url, body) }
            if (response.code !in 200..299) {
                // 截断响应体，避免整串原始 JSON 灌进设置页状态栏
                log.warn("REST $method $path 失败：HTTP ${response.code}, body=${response.body.take(200)}")
                OpenCodeResult.failure(IOException("HTTP ${response.code}: ${response.body.take(200)}"))
            } else {
                OpenCodeResult.success(parse(response.body))
            }
        } catch (e: IOException) {
            log.warn("REST $method $path 异常：${e.message}")
            OpenCodeResult.failure(e)
        }
    }

    /**
     * OkHttp 同步请求（PATCH 与其余方法同路，OkHttp 的 [Request.Builder.method] 接受任意方法名）
     */
    private fun okHttpRequest(method: String, url: String, body: String?): RawResponse {
        val request = Request.Builder()
            .url(url)
            .method(method, body?.let { RequestBody.create(JSON_MEDIA_TYPE, it) })
            .apply {
                if (body != null) header("Content-Type", "application/json; charset=utf-8")
                authHeaderValue()?.let { header("Authorization", it) }
            }
            .build()
        httpClient.newCall(request).execute().use { response ->
            return RawResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private class RawResponse(val code: Int, val body: String)

    companion object {
        /** JSON 请求体媒体类型（OkHttp） */
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

// ==================== URL 拼接工具（同包顶层 internal，供客户端与 Lookup 端点共用） ====================

internal fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8")

/** 拼接 location.directory 查询参数 */
internal fun directoryQuery(directory: String?): String =
    directory?.takeIf { it.isNotBlank() }
        ?.let { "?location.directory=" + URLEncoder.encode(it, "UTF-8") }
        ?: ""

/** `location[directory]` 查询项（openapi 声明为 deepObject；fs/command 的相对路径以此为基准） */
internal fun locationPair(directory: String?): Pair<String, String>? =
    directory?.takeIf { it.isNotBlank() }?.let { "location[directory]" to it }

/** 拼接查询串（键与值均做 URL 编码） */
internal fun queryString(params: List<Pair<String, String>>): String =
    params.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "&", prefix = "?") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        ?: ""

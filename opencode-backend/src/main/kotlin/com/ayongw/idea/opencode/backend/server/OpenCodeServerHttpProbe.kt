package com.ayongw.idea.opencode.backend.server

import com.ayongw.idea.opencode.backend.repository.OpenCodeHttpClientFactory
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 单次探测的 HTTP 响应（只保留分类所需的两个字段） */
data class OpenCodeServerHttpResponse(val statusCode: Int, val body: String)

/** HTTP 探测传输：一次 `GET`，短超时、显式关代理 */
fun interface OpenCodeServerHttpProbe {
    /** @throws java.io.IOException 连接被拒 / 连接或读取超时 */
    fun get(url: String, authHeader: String?, connectTimeoutMs: Int, readTimeoutMs: Int): OpenCodeServerHttpResponse
}

/**
 * 默认探测传输：共享 OkHttp 基座（TSD-30 §5.9）派生
 *
 * - 与 `OpenCodeRestClient`/事件流同一技术栈，连接池/dispatcher 随工厂统一管理；
 * - [Proxy.NO_PROXY] 显式关闭代理，避免回环探测被系统/IDE 代理改写（TSD-31 §7 R3）；
 * - 探测无正文错误体（错误分类只看状态码），非 2xx 返回空串，与 JDK 实现行为一致。
 */
class OkHttpProbe : OpenCodeServerHttpProbe {

    /** 关代理派生客户端（共享基座的连接池/dispatcher），调用级超时再按需派生 */
    private val noProxyClient = OpenCodeHttpClientFactory.shared.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .build()

    override fun get(
        url: String,
        authHeader: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): OpenCodeServerHttpResponse {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        val client = noProxyClient.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        client.newCall(request).execute().use { response ->
            val body = if (response.code in 200..299) {
                response.body?.string().orEmpty()
            } else {
                ""
            }
            return OpenCodeServerHttpResponse(response.code, body)
        }
    }
}

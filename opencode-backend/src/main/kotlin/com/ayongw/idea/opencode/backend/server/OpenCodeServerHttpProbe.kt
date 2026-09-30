package com.ayongw.idea.opencode.backend.server

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/** 单次探测的 HTTP 响应（只保留分类所需的两个字段） */
data class OpenCodeServerHttpResponse(val statusCode: Int, val body: String)

/** HTTP 探测传输：一次 `GET`，短超时、显式关代理 */
fun interface OpenCodeServerHttpProbe {
    /** @throws java.io.IOException 连接被拒 / 连接或读取超时 */
    fun get(url: String, authHeader: String?, connectTimeoutMs: Int, readTimeoutMs: Int): OpenCodeServerHttpResponse
}

/**
 * 默认探测传输：JDK [HttpURLConnection] + [Proxy.NO_PROXY]
 *
 * - 与同模块 `OpenCodeRestClient` 同一技术栈，不额外引入客户端（okhttp 只服务事件流）；
 * - [Proxy.NO_PROXY] 显式关闭代理，避免回环探测被系统/IDE 代理改写（TSD-31 §7 R3）；
 * - 不用平台 `HttpRequests`：它位于 `intellij.platform.ide.core`，引入会扩张 backend 模块（split mode）依赖面，行为与此处等价。
 */
class JdkHttpProbe : OpenCodeServerHttpProbe {

    override fun get(
        url: String,
        authHeader: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): OpenCodeServerHttpResponse {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            authHeader?.let { connection.setRequestProperty("Authorization", it) }

            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                ""
            }
            return OpenCodeServerHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
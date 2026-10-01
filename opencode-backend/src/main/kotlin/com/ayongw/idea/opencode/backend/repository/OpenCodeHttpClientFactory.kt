package com.ayongw.idea.opencode.backend.repository

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OpenCode 插件内共享的 OkHttpClient 工厂（TSD-30 §5.9）：
 * REST 与事件流共用同一基座实例（同一连接池/调度线程池/一份配置），
 * 差异化超时通过 [OkHttpClient.newBuilder] 派生（仍共享连接池与 dispatcher）。
 *
 * 注意：基座实例为进程级共享，任何持有方（如事件流 stop）不得关闭
 * dispatcher 线程池或清空连接池，只允许取消自己发起的调用。
 */
object OpenCodeHttpClientFactory {

    /** 连接超时（毫秒） */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /** REST 读取超时（毫秒） */
    const val REST_READ_TIMEOUT_MS = 30_000L

    /**
     * 共享基座客户端（REST 直接使用；事件流经 [forEventStream] 派生）
     *
     * retryOnConnectionFailure=false：连接重试由调用方（事件流重连/REST 调用处）统一控制，
     * 避免 OkHttp 在流式场景下静默重试。
     */
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REST_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 事件流派生客户端：仅覆盖读超时（心跳保活判定），其余配置与连接池共享基座 */
    fun forEventStream(readTimeoutMillis: Long): OkHttpClient =
        shared.newBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
}

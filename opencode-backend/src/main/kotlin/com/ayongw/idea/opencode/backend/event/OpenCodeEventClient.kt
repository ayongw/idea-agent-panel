package com.ayongw.idea.opencode.backend.event

import com.ayongw.idea.opencode.backend.repository.OpenCodeAuth
import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * opencode v2 `/api/event` 事件流客户端（okhttp + okhttp-sse）
 *
 * - 事件类型从 `data` JSON 的 `type` 字段解析（服务端不发 `event:` 行）
 * - 断线走指数退避重连；401/403 视为凭据无效，停止重连交由设置页处理
 * - 读超时非 0：服务端以 `: heartbeat` 注释帧保活，超时即判定链路已死并重连
 * - 线程纪律：回调内只做解析与向上抛事件，不触碰 UI
 *
 * 契约见 docs/tsd/TSD-06-事件流接入设计.md §4、§5.3。
 */
class OpenCodeEventClient(
    baseUrl: String,
    private val username: String = OpenCodeAuth.DEFAULT_USERNAME,
    private val password: String? = null,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onStateChanged: (State) -> Unit = {},
    private val initialBackoffMillis: Long = 1_000L,
    private val maxBackoffMillis: Long = 30_000L,
    private val readTimeoutMillis: Long = 60_000L
) {

    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, UNAUTHORIZED, STOPPED }

    private val url: String = baseUrl.trim().trimEnd('/') + EVENT_PATH

    private val log = Logger.getInstance(OpenCodeEventClient::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)   // 重连由本类统一控制
        .build()

    private val factory = EventSources.createFactory(client)
    private val lock = Any()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "opencode-event-client").apply { isDaemon = true }
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var state: State = State.IDLE

    private var eventSource: EventSource? = null
    private var backoffMillis = initialBackoffMillis

    /** 当前连接状态（只读） */
    val currentState: State get() = state

    /** 建立事件流连接（幂等：重复调用只保留最后一次连接） */
    fun start() {
        if (stopped) return
        connect()
    }

    /** 关闭连接并释放线程/连接池；关闭后不可再 start */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            eventSource?.cancel()
            eventSource = null
        }
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        updateState(State.STOPPED)
    }

    private fun connect() {
        updateState(if (backoffMillis > initialBackoffMillis) State.RECONNECTING else State.CONNECTING)
        // 与 stop() 串行化：避免 stop 之后仍由重连调度创建出连接
        synchronized(lock) {
            if (stopped) return
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
            OpenCodeAuth.basicHeader(username, password)?.let { builder.header("Authorization", it) }
            eventSource = factory.newEventSource(builder.build(), listener)
        }
    }

    private val listener = object : EventSourceListener() {

        override fun onOpen(eventSource: EventSource, response: Response) {
            backoffMillis = initialBackoffMillis
            log.info("事件流已连接：$url（HTTP ${response.code}）")
            updateState(State.CONNECTED)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (data.isBlank()) return          // 心跳等注释帧不会走到这里，防御空帧
            OpenCodeEventParser.parse(data)?.let(onEvent)
        }

        override fun onClosed(eventSource: EventSource) {
            log.info("事件流已关闭（服务端断开），准备重连")
            scheduleReconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val code = response?.code
            if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
                // 凭据无效：不再重连（日志只记状态码，不记账号密码）
                log.warn("事件流认证失败（HTTP $code）：$url，请检查设置页的凭据")
                updateState(State.UNAUTHORIZED)
                return
            }
            log.warn("事件流连接失败（HTTP ${code ?: "-"}）：$url: ${t?.message ?: t?.javaClass?.simpleName ?: "未知原因"}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        updateState(State.RECONNECTING)
        val delay = backoffMillis
        backoffMillis = (backoffMillis * BACKOFF_FACTOR).coerceAtMost(maxBackoffMillis)
        log.info("事件流将在 ${delay}ms 后重连")
        runCatching {
            scheduler.schedule({ runCatching { connect() } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun updateState(newState: State) {
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        const val EVENT_PATH = "/api/event"
        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val BACKOFF_FACTOR = 2
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
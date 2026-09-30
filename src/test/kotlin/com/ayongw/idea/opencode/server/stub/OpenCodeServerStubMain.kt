package com.ayongw.idea.opencode.server.stub

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 测试用「短命 opencode serve」桩进程（TSD-31 §10.1）
 *
 * 用 JVM 自身作为桩（跨平台、无需 shell），由 `OpenCodeServerLauncherITest` 以真实子进程拉起：
 *
 * ```
 * java -cp <test classpath> com.ayongw.idea.opencode.server.stub.OpenCodeServerStubMain --port <port>
 * ```
 *
 * 形态由 `--mode <mode>` 参数（或环境变量 `OPENCODE_STUB_MODE`）控制：
 * - `ok`（默认）：`GET /api/info` 返回 `{"pid":<本进程 pid>,"version":"stub"}`
 * - `unauthorized`：返回 401（验证「他人实例需密钥」路径）
 * - `notjson`：返回 200 + HTML（验证 SPA fallback 不误判）
 * - `slow`：响应前睡 3s（验证超时/慢启动）
 * - `exit`：立即以退出码 3 退出（验证「就绪前退出」）
 */
object OpenCodeServerStubMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().zipWithNext().filter { it.first.startsWith("--") }.associate { it.first to it.second }
        val port = options["--port"]?.toIntOrNull() ?: 0
        val mode = options["--mode"] ?: System.getenv("OPENCODE_STUB_MODE") ?: MODE_OK
        println("stub starting mode=$mode requestedPort=$port")

        if (mode == MODE_EXIT) {
            System.err.println("stub exiting immediately")
            System.out.flush()
            System.exit(3)
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/api/info") { exchange ->
            when (mode) {
                MODE_UNAUTHORIZED -> respond(exchange, 401, """{"error":"unauthorized"}""")
                MODE_NOT_JSON -> respond(exchange, 200, "<html><body>not opencode</body></html>")
                MODE_SLOW -> {
                    Thread.sleep(3_000)
                    respond(exchange, 200, infoBody())
                }

                else -> respond(exchange, 200, infoBody())
            }
        }
        server.start()
        println("stub listening on http://127.0.0.1:${server.address.port} pid=${ProcessHandle.current().pid()}")
        System.out.flush()

        // 非守护的 HTTP 线程会维持 JVM；显式阻塞主线程，等待外部终止（SIGTERM 由 JVM 默认处理）
        try {
            Thread.sleep(Long.MAX_VALUE)
        } catch (_: InterruptedException) {
            // 忽略：收到终止信号后退出
        }
    }

    private fun infoBody(): String =
        """{"pid":${ProcessHandle.current().pid()},"version":"stub"}"""

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    const val MODE_OK = "ok"
    const val MODE_UNAUTHORIZED = "unauthorized"
    const val MODE_NOT_JSON = "notjson"
    const val MODE_SLOW = "slow"
    const val MODE_EXIT = "exit"
}
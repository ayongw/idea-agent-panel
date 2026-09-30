package com.ayongw.idea.opencode.backend.mcp

import com.ayongw.idea.opencode.shared.McpServerDto
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MCP 工具清单客户端
 *
 * - `local`：按配置的 `command`/`environment`/`cwd` 起一个**短命子进程**，stdio 上跑
 *   `initialize → notifications/initialized → tools/list`，结束即强制回收
 * - `remote`：Streamable HTTP（POST + `Accept: application/json, text/event-stream`），
 *   回带 `Mcp-Session-Id` 与 `MCP-Protocol-Version`
 *
 * 只依赖 JDK（不使用平台 API），便于单测直接构造；opencode 侧没有该数据，故由本插件自连（见 TSD-32）。
 */
class McpToolsClient(
    private val initializeTimeoutMs: Long = DEFAULT_INITIALIZE_TIMEOUT_MS,
    private val listTimeoutMs: Long = DEFAULT_LIST_TIMEOUT_MS,
    /** 登录 shell 的 PATH 探测：IDE 进程 PATH 很窄，裸命令（`codegraph`）会找不到（见 [LoginShellPath]） */
    private val loginShellPath: LoginShellPath = LoginShellPath()
) {

    fun listTools(server: McpServerDto, workingDir: Path? = null): McpToolsResult =
        when (server.type) {
            TYPE_REMOTE -> listRemote(server)
            else -> listLocal(server, workingDir)
        }

    // ==================== local（stdio） ====================

    private fun listLocal(server: McpServerDto, workingDir: Path?): McpToolsResult {
        if (server.command.isEmpty()) {
            return McpToolsResult.Failure("配置里没有 command，无法启动该服务器")
        }
        val builder = ProcessBuilder(server.command)
        resolveDir(server.cwd, workingDir)?.let { builder.directory(it.toFile()) }
        val environment = builder.environment()
        // IDE 进程 PATH 通常只有 /usr/bin:/bin:...，裸命令（codegraph / npx 装在 nvm、homebrew 下）会找不到，
        // 用登录 shell 的 PATH 补上；配置里显式声明的 environment 优先级最高（见下）
        loginShellPath.effectivePath(environment["PATH"])?.let { environment["PATH"] = it }
        if (server.environment.isNotEmpty()) {
            environment.putAll(server.environment.mapValues { expandHome(it.value) })
        }
        val process = try {
            builder.start()
        } catch (e: Exception) {
            return McpToolsResult.Failure("启动失败：${e.message ?: e.toString()}")
        }

        val stderrTail = TailBuffer()
        val channel = StdioChannel(process, stderrTail)
        return try {
            channel.send(McpProtocol.initializeRequest(INITIALIZE_ID))
            val initialize = channel.await(INITIALIZE_ID, initializeTimeoutMs)
                ?: return channel.unavailable("初始化", initializeTimeoutMs)
            McpProtocol.errorMessageOf(initialize)?.let { return McpToolsResult.Failure(it) }
            channel.send(McpProtocol.initializedNotification())
            collectTools(
                next = { id, cursor ->
                    channel.send(McpProtocol.toolsListRequest(id, cursor))
                    channel.await(id, listTimeoutMs)
                },
                unavailable = { channel.unavailable("工具清单请求", listTimeoutMs) }
            )
        } finally {
            channel.close()
        }
    }

    // ==================== remote（Streamable HTTP） ====================

    private fun listRemote(server: McpServerDto): McpToolsResult {
        val url = server.url?.takeIf { it.isNotBlank() }
            ?: return McpToolsResult.Failure("配置里没有 url，无法连接该服务器")
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(initializeTimeoutMs))
            .build()
        var sessionId: String? = null
        var protocolVersion: String? = null
        var unavailableReason: String? = null

        fun post(body: String, timeoutMs: Long): JsonObject? {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply {
                    server.headers.forEach { (key, value) -> header(key, value) }
                    sessionId?.let { header("Mcp-Session-Id", it) }
                    protocolVersion?.let { header("MCP-Protocol-Version", it) }
                }
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                unavailableReason = "网络不可达：${e.message ?: e.toString()}"
                return null
            }
            val code = response.statusCode()
            if (code !in 200..299) {
                unavailableReason = httpError(code, url, response.body())
                return null
            }
            response.headers().firstValue("Mcp-Session-Id").orElse(null)?.let { sessionId = it }
            return parseBody(response)
        }

        val initialize = post(McpProtocol.initializeRequest(INITIALIZE_ID), initializeTimeoutMs)
            ?: return McpToolsResult.Failure(unavailableReason ?: "初始化失败")
        McpProtocol.errorMessageOf(initialize)?.let { return McpToolsResult.Failure(it) }
        protocolVersion = McpProtocol.resultOf(initialize)?.get("protocolVersion")?.let { element ->
            if (element.isJsonPrimitive) element.asString else null
        } ?: McpProtocol.PROTOCOL_VERSION
        // 通知：无响应体，失败不致命（后续 tools/list 会暴露真实错误）
        post(McpProtocol.initializedNotification(), initializeTimeoutMs)

        return collectTools(
            next = { id, cursor -> post(McpProtocol.toolsListRequest(id, cursor), listTimeoutMs) },
            unavailable = { McpToolsResult.Failure(unavailableReason ?: "未取得工具清单响应") }
        )
    }

    /** 响应体解析：`text/event-stream` 时按 `data:` 取第一段可用 JSON；空体（如通知的 202）返回 null */
    private fun parseBody(response: HttpResponse<String>): JsonObject? {
        val body = response.body().orEmpty()
        if (body.isBlank()) return null
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .mapNotNull { McpProtocol.parse(it) }
                .firstOrNull()
        } else {
            McpProtocol.parse(body)
        }
    }

    private fun httpError(code: Int, url: String, body: String): String = when {
        code == 401 || code == 403 -> "需授权（OAuth）：该服务器要求先完成授权"
        (code == 404 || code == 405) && url.contains("/sse") -> "暂不支持该传输方式（遗留 SSE 端点）"
        else -> "HTTP $code：${body.take(ERROR_BODY_CHARS)}"
    }

    // ==================== 公共：tools/list（含分页） ====================

    /**
     * @param next 返回 null 表示通道不可用（超时/断开），由 [unavailable] 生成失败结果
     */
    private fun collectTools(
        next: (Int, String?) -> JsonObject?,
        unavailable: () -> McpToolsResult.Failure
    ): McpToolsResult {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var id = TOOLS_ID
        var pages = 0
        while (true) {
            val message = next(id, cursor) ?: return unavailable()
            McpProtocol.errorMessageOf(message)?.let { return McpToolsResult.Failure(it) }
            val result = McpProtocol.resultOf(message) ?: return McpToolsResult.Failure("响应缺少 result 字段")
            tools += McpProtocol.toolsOf(result)
            cursor = McpProtocol.cursorOf(result) ?: break
            pages++
            if (pages >= McpProtocol.MAX_PAGES) {
                return McpToolsResult.Success(tools, "分页超限，仅显示前 ${tools.size} 个")
            }
            id++
        }
        return McpToolsResult.Success(tools)
    }

    // ==================== stdio 通道 ====================

    private class StdioChannel(private val process: Process, private val stderrTail: TailBuffer) : AutoCloseable {

        private val lines = LinkedBlockingQueue<String>()
        private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        init {
            startDaemon("mcp-stdio-stdout") {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).forEachLine {
                        lines.put(it)
                    }
                }
            }
            startDaemon("mcp-stdio-stderr") {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).forEachLine {
                        stderrTail.append(it)
                    }
                }
            }
        }

        fun send(message: String) {
            runCatching {
                writer.write(message)
                writer.newLine()
                writer.flush()
            }
        }

        /** 读取 id 匹配的响应；非 JSON 行（部分 server 的日志）与其它 id 的消息忽略 */
        fun await(id: Int, timeoutMs: Long): JsonObject? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val remain = deadline - System.nanoTime()
                if (remain <= 0) return null
                val line = lines.poll(remain, TimeUnit.NANOSECONDS) ?: return null
                val message = McpProtocol.parse(line) ?: continue
                if (McpProtocol.idOf(message) == id) return message
            }
        }

        /** 通道不可用时的失败结果：区分「进程已退出」与「超时」，并附 stderr 尾部 */
        fun unavailable(what: String, timeoutMs: Long): McpToolsResult.Failure {
            val reason = if (!process.isAlive) {
                val code = runCatching { process.exitValue() }.getOrNull()
                "进程已退出（exit=${code ?: "?"}）"
            } else {
                "超时：${(timeoutMs + 999) / 1000} 秒内未完成$what"
            }
            val tail = stderrTail.text()
            return McpToolsResult.Failure(if (tail.isBlank()) reason else "$reason；stderr: $tail")
        }

        override fun close() {
            process.descendants().forEach { runCatching { it.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            runCatching { writer.close() }
        }

        private fun startDaemon(name: String, body: () -> Unit) {
            Thread(body, name).apply { isDaemon = true }.start()
        }
    }

    /** stderr 尾部缓存：只留末尾若干字符，避免长日志占内存 */
    private class TailBuffer(private val limit: Int = MAX_STDERR_CHARS) {

        private val buffer = StringBuilder()

        @Synchronized
        fun append(line: String) {
            buffer.append(line).append('\n')
            if (buffer.length > limit * 2) buffer.delete(0, buffer.length - limit)
        }

        @Synchronized
        fun text(): String = buffer.toString().trim().takeLast(limit)
    }

    // ==================== 小工具 ====================

    /** 起进程的工作目录：配置里的 `cwd`（相对路径按工作区解析）优先，缺省用工作区目录 */
    private fun resolveDir(cwd: String?, workingDir: Path?): Path? {
        val raw = cwd?.takeIf { it.isNotBlank() } ?: return workingDir
        val path = Path.of(expandHome(raw))
        return if (path.isAbsolute) path else workingDir?.resolve(path) ?: path
    }

    /** `~` / `~/x` 展开为用户主目录（配置里常见 `MEMORY_DIR: ~/memory`） */
    private fun expandHome(value: String): String =
        if (value == "~" || value.startsWith("~/")) {
            System.getProperty("user.home") + value.removePrefix("~")
        } else {
            value
        }

    companion object {
        const val TYPE_REMOTE = "remote"

        private const val INITIALIZE_ID = 1
        private const val TOOLS_ID = 2
        private const val DEFAULT_INITIALIZE_TIMEOUT_MS = 3_000L
        private const val DEFAULT_LIST_TIMEOUT_MS = 5_000L
        private const val ERROR_BODY_CHARS = 200
        private const val MAX_STDERR_CHARS = 2_048
    }
}
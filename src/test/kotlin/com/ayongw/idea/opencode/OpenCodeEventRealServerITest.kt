package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.BackendChatRepositoryModel
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.TestApplicationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 真实连接集成验证（见 TSD-06 §9.1）：事件流 → 后端会话状态 的整链路。
 *
 * 默认不执行（Gradle `test` 排除 `*ITest`）。运行方式：
 * ```
 * OPENCODE_SERVER_PASSWORD=itest-oc-panel opencode serve --port 4097 &
 * ./gradlew test -Pit=true --tests "com.ayongw.idea.opencode.OpenCodeEventRealServerITest"
 * ```
 * 环境变量：`OPENCODE_IT_BASE_URL`（默认 `http://127.0.0.1:4097`）、`OPENCODE_IT_PASSWORD`（默认 `itest-oc-panel`）。
 *
 * 为什么必须真机跑：mock 只能验证解析与重连逻辑，鉴权、模型未授权、真实帧字段差异只有真机才暴露。
 */
class OpenCodeEventRealServerITest {

    private val baseUrl: String = System.getenv("OPENCODE_IT_BASE_URL") ?: "http://127.0.0.1:4097"
    private val password: String = System.getenv("OPENCODE_IT_PASSWORD") ?: "itest-oc-panel"

    private lateinit var model: BackendChatRepositoryModel
    private lateinit var project: Project

    @Before
    fun setUp() {
        assumeTrue("需要 -Dopencode.it=true 且本机有可达的 opencode serve", System.getProperty("opencode.it") == "true")
        // BackendChatRepositoryModel 是 Project 服务（需 Project 以在销毁时释放 Server 引用）
        TestApplicationManager.getInstance()
        project = ProjectManager.getInstance()
            .createProject("opencode-itest", Files.createTempDirectory("opencode-itest").toString())
        model = BackendChatRepositoryModel(project)
        model.updateServerConfig(baseUrl, "opencode", password)
    }

    @After
    fun tearDown() {
        if (::model.isInitialized) model.dispose()
        if (::project.isInitialized) runCatching { ProjectManager.getInstance().closeAndDispose(project) }
    }

    @Test
    fun streamingDeliversIncrementalTextAndRunningLifecycle() = runBlocking {
        val sessionId = requireNotNull(withTimeout(CREATE_TIMEOUT_MS) { model.createNewSession("itest streaming") }) {
            "应能创建会话（检查 $baseUrl 是否可达、密码是否正确）"
        }

        // 默认模型可能未授权，固定切到 OpenCode Zen 免费模型
        withTimeout(REST_TIMEOUT_MS) { model.switchModel(sessionId, "opencode", FREE_MODEL_ID) }

        val snapshots = CopyOnWriteArrayList<List<ChatMessageDto>>()
        val collector = launch { model.getMessagesFlow().collect { messages -> snapshots.add(messages) } }

        val sender = launch { model.sendMessage("Reply with exactly: $EXPECTED_TEXT") }
        withTimeout(REST_TIMEOUT_MS) { model.getSessionRunningFlow().first { it } }

        // 成功与失败都会把执行态置回 false
        withTimeout(STREAM_TIMEOUT_MS) { model.getSessionRunningFlow().first { !it } }
        sender.join()

        withTimeout(STREAM_TIMEOUT_MS) {
            while (snapshots.none { snapshot -> snapshot.any { it.isAssistantText(EXPECTED_TEXT) } }) delay(POLL_INTERVAL_MS)
        }
        collector.cancel()

        val messages = snapshots.last()
        assertTrue(
            "应无失败气泡（实际：${messages.map { it.content }}）",
            messages.none { it.content.contains("provider.invalid-request") }
        )
        assertTrue(
            "流式应分多次推送中间态，而不是只在结束时一次（实际 ${snapshots.size} 次）",
            snapshots.size >= 2
        )
        assertTrue(
            "思考过程应可见",
            messages.any { it.type == ChatMessage.ChatMessageType.AI_THINKING && it.content.isNotBlank() }
        )
        assertTrue(
            "正文应以 text.ended 全文校准为 $EXPECTED_TEXT（实际：${messages.map { it.content }}）",
            messages.any { it.isAssistantText(EXPECTED_TEXT) }
        )
    }

    /** 权限链路（实测契约）：`permission.asked` → 回复 `once` → 执行继续并收尾 */
    @Test
    fun permissionRequestCanBeAnswered() = runBlocking {
        val sessionId = requireNotNull(withTimeout(CREATE_TIMEOUT_MS) { model.createNewSession("itest permission") }) {
            "应能创建会话（检查 $baseUrl 是否可达、密码是否正确）"
        }
        withTimeout(REST_TIMEOUT_MS) { model.switchModel(sessionId, "opencode", FREE_MODEL_ID) }

        withTimeout(REST_TIMEOUT_MS) {
            model.sendMessage("Use the bash tool to run exactly this command: echo hello > $PERMISSION_FILE")
        }

        var answered = 0
        val deadline = System.currentTimeMillis() + STREAM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pending = model.getPendingPermissionFlow().first()
            if (pending != null) {
                withTimeout(REST_TIMEOUT_MS) {
                    model.replyPermission(pending.requestId, OpenCodeRestClient.PermissionDecision.ONCE)
                }
                answered++
                continue
            }
            if (!model.getSessionRunningFlow().first()) break
            delay(POLL_INTERVAL_MS)
        }

        assumeTrue("本机未触发权限请求（可能已配置自动允许），跳过", answered > 0)
        assertFalse("回复权限后执行应收尾", model.getSessionRunningFlow().first())
    }

    private fun ChatMessageDto.isAssistantText(expected: String): Boolean =
        !isMyMessage && type == ChatMessage.ChatMessageType.TEXT && content == expected

    /** 中断链路（实测契约）：`step.failed(aborted)` + `session.execution.interrupted`，且不产生失败气泡 */
    @Test
    fun interruptEndsRunningWithoutFailureBubble() = runBlocking {
        val sessionId = requireNotNull(withTimeout(CREATE_TIMEOUT_MS) { model.createNewSession("itest interrupt") }) {
            "应能创建会话（检查 $baseUrl 是否可达、密码是否正确）"
        }
        withTimeout(REST_TIMEOUT_MS) { model.switchModel(sessionId, "opencode", FREE_MODEL_ID) }

        withTimeout(REST_TIMEOUT_MS) { model.sendMessage("Write a 2000-word essay about the history of computing.") }
        withTimeout(REST_TIMEOUT_MS) { model.getSessionRunningFlow().first { it } }

        delay(INTERRUPT_AFTER_MS)
        withTimeout(REST_TIMEOUT_MS) { model.abortExecution() }

        // 服务端把会话标成 interrupted 才算真的中断成功
        withTimeout(STREAM_TIMEOUT_MS) {
            while (model.getRestClient().getSession(sessionId).getOrNull()?.outcome != INTERRUPTED_OUTCOME) {
                delay(POLL_INTERVAL_MS)
            }
        }
        withTimeout(REST_TIMEOUT_MS) { model.getSessionRunningFlow().first { !it } }
        assertFalse("中断后应退出执行态", model.getSessionRunningFlow().first())
        assertTrue(
            "用户中断不应出现失败气泡（实际：${model.getMessagesFlow().first().map { it.content }}）",
            model.getMessagesFlow().first().none { it.content.contains("Step interrupted") }
        )
    }

    private companion object {
        const val FREE_MODEL_ID = "mimo-v2.6-flash-free"
        const val EXPECTED_TEXT = "PONG"
        const val INTERRUPTED_OUTCOME = "interrupted"
        const val PERMISSION_FILE = "/tmp/oc-panel-itest-perm.txt"
        const val CREATE_TIMEOUT_MS = 20_000L
        const val REST_TIMEOUT_MS = 30_000L
        const val STREAM_TIMEOUT_MS = 60_000L
        const val INTERRUPT_AFTER_MS = 3_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
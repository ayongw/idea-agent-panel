package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.backend.repository.OpenCodeResult
import com.ayongw.idea.opencode.backend.repository.getInfo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REST 侧统一 OkHttp 传输后的契约用例（TSD-30 §5.9，MockWebServer 断言真实请求）
 *
 * 覆盖：PATCH 方法正确发送（HttpURLConnection 无法表达，OkHttp 可）、401 映射为失败、
 * 200 + `{ "data": ... }` 正常解析。
 */
class OpenCodeRestClientOkHttpUnitTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenCodeRestClient(server.url("/").toString().trimEnd('/'), "opencode", "secret")

    @Test
    fun patchRenameSessionSendsPatchMethodWithBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"id":"ses_1","title":"新标题","agent":"build"}}"""))

        val result = client().renameSession("ses_1", "新标题")

        assertTrue("PATCH 重命名应成功", result.isSuccess())
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/session/ses_1", recorded.path)
        assertTrue("应携带 JSON 请求体", recorded.body.readUtf8().contains("新标题"))
    }

    @Test
    fun unauthorizedMapsToFailureWithCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val result = client().getInfo()

        assertTrue("401 应为失败", result.isFailure())
        val failure = result as? OpenCodeResult.Failure
        assertTrue("错误信息应含 HTTP 401", failure?.exception?.message?.contains("401") == true)
    }

    @Test
    fun successParsesDataWrappedObject() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"version":"2.0.18","pid":1234,"urls":{"api":"http://127.0.0.1:4096/api"}}}"""
            )
        )

        val info = client().getInfo().getOrNull()

        assertTrue("应解析出 version", info != null && info.get("version").asString == "2.0.18")
    }
}

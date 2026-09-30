package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpointResolver
import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpointSource
import com.ayongw.idea.opencode.backend.server.OpenCodeServerUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址来源优先级矩阵（TSD-31 §3.4）
 *
 * 覆盖：显式设置项优先、环境变量覆盖默认、备用端口顺序、去重、非法值回落、绑定端口候选。
 */
class OpenCodeServerEndpointResolverUnitTest {

    private fun resolver(env: Map<String, String> = emptyMap()) =
        OpenCodeServerEndpointResolver(env = { env[it] })

    @Test
    fun `显式设置项永远第一个探测`() {
        val candidates = resolver().discoveryCandidates(
            settingsUrl = "http://127.0.0.1:8080",
            username = "opencode",
            password = null,
        )

        val first = candidates.first()
        assertEquals("http://127.0.0.1:8080", first.baseUrl)
        assertEquals(8080, first.port)
        assertEquals(OpenCodeServerEndpointSource.SETTINGS, first.source)
        assertFalse("探测候选不代表归属，须为非自有", first.owned)
        assertEquals("默认地址紧随其后", "http://127.0.0.1:4096", candidates[1].baseUrl)
    }

    @Test
    fun `设置项为默认值时按默认端口起算且不重复`() {
        val candidates = resolver().discoveryCandidates(
            settingsUrl = OpenCodeServerEndpointResolver.DEFAULT_SERVER_URL,
            username = "opencode",
            password = null,
        )

        assertEquals(OpenCodeServerEndpointSource.DEFAULT_PORT, candidates.first().source)
        assertEquals(
            "默认地址 + 8 个备用端口，且无重复",
            1 + OpenCodeServerEndpointResolver.FALLBACK_PORT_COUNT,
            candidates.size,
        )
        assertEquals("候选地址不应重复", candidates.size, candidates.map { it.baseUrl }.distinct().size)
    }

    @Test
    fun `环境变量覆盖默认地址且来源标记为 ENV`() {
        val candidates = resolver(mapOf(OpenCodeServerEndpointResolver.ENV_SERVER_URL to "http://127.0.0.1:5000"))
            .discoveryCandidates(settingsUrl = "  ", username = "opencode", password = null)

        assertEquals("http://127.0.0.1:5000", candidates.first().baseUrl)
        assertEquals(OpenCodeServerEndpointSource.ENV, candidates.first().source)
    }

    @Test
    fun `探测顺序为默认端口再到备用端口`() {
        val ports = resolver().discoveryCandidates(settingsUrl = null, username = "opencode", password = null).map { it.port }

        assertEquals(4096, ports.first())
        assertEquals(listOf(4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104), ports)
    }

    @Test
    fun `设置项落在备用端口时只出现一次并保留 SETTINGS 来源`() {
        val candidates = resolver().discoveryCandidates(
            settingsUrl = "http://127.0.0.1:4097",
            username = "opencode",
            password = null,
        )

        assertEquals("备用端口候选与设置项重合时只保留一条", 1, candidates.count { it.port == 4097 })
        val first = candidates.first()
        assertEquals(4097, first.port)
        assertEquals(OpenCodeServerEndpointSource.SETTINGS, first.source)
        assertEquals("默认端口仍在候选内", 4096, candidates[1].port)
    }

    @Test
    fun `非法地址回落默认端口序列`() {
        val candidates = resolver().discoveryCandidates(settingsUrl = "not-a-url", username = "opencode", password = null)

        assertEquals(4096, candidates.first().port)
        assertEquals(OpenCodeServerEndpointSource.DEFAULT_PORT, candidates.first().source)
    }

    @Test
    fun `绑定端口默认从 4096 起并带备用端口`() {
        val ports = resolver().bindPortCandidates(settingsUrl = null)

        assertEquals(4096, ports.first())
        assertTrue("应带备用端口", ports.containsAll(listOf(4097, 4098)))
    }

    @Test
    fun `显式指定非默认端口时只绑定该端口且不静默换端口`() {
        val ports = resolver().bindPortCandidates(settingsUrl = "http://127.0.0.1:5099")

        assertEquals(listOf(5099), ports)
    }

    @Test
    fun `显式指向默认端口时仍按默认序列绑定`() {
        val ports = resolver().bindPortCandidates(settingsUrl = OpenCodeServerEndpointResolver.DEFAULT_SERVER_URL)

        assertEquals(4096, ports.first())
        assertTrue(ports.size > 1)
    }

    @Test
    fun `环境变量覆盖时绑定首选该端口`() {
        val ports = resolver(mapOf(OpenCodeServerEndpointResolver.ENV_SERVER_URL to "http://127.0.0.1:5000"))
            .bindPortCandidates(settingsUrl = "")

        assertEquals(5000, ports.first())
        assertTrue("备用端口仍作为冲突兜底", ports.contains(4097))
    }

    @Test
    fun `地址解析与 userinfo 剥离`() {
        assertEquals(4096, OpenCodeServerUrls.portOf("http://127.0.0.1:4096"))
        assertEquals(80, OpenCodeServerUrls.portOf("http://127.0.0.1"))
        assertEquals(443, OpenCodeServerUrls.portOf("https://example.com"))
        assertFalse("无 host 视为非法", OpenCodeServerUrls.portOf("://bad") != null)
        assertEquals("http://127.0.0.1:4096", OpenCodeServerUrls.stripUserInfo("http://user:pass@127.0.0.1:4096"))
    }

    @Test
    fun `端点 toString 与展示地址不泄漏密码`() {
        val endpoint = resolver().discoveryCandidates(
            settingsUrl = "http://user:pass@127.0.0.1:4096",
            username = "opencode",
            password = "top-secret",
        ).first()

        assertEquals("http://127.0.0.1:4096", endpoint.displayUrl)
        val text = endpoint.toString()
        assertFalse("toString 不得含密码原文", text.contains("top-secret"))
        assertFalse("displayUrl 不得含 userinfo", endpoint.displayUrl.contains("pass"))
    }
}
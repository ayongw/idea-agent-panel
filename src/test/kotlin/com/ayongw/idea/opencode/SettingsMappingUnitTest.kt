package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.SettingsMapping
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 设置映射验证：配置（V1/V2 两种写法）× 服务端目录 × 运行状态的合并规则
 */
class SettingsMappingUnitTest {

    private fun json(text: String): JsonObject =
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.LENIENT
            JsonParser.parseReader(reader).asJsonObject
        }

    @Test
    fun providerConfigOverridesGlobalAndCarriesScope() {
        val global = json(
            """
            {"providers":{"hello-tw":{"name":"两轮全局","package":"aisdk:@ai-sdk/openai-compatible",
              "settings":{"baseURL":"http://global"},"models":{"old":{}}}}}
            """.trimIndent()
        )
        val project = json(
            """
            {"providers":{"hello-tw":{"name":"两轮项目","settings":{"baseURL":"http://project"},
              "models":{"GLM-5.2":{}}}}}
            """.trimIndent()
        )

        val providers = SettingsMapping.providers(global, project, emptyList(), emptyList())

        val provider = providers.single()
        assertEquals("hello-tw", provider.id)
        assertEquals("两轮项目", provider.name)
        assertEquals("aisdk:@ai-sdk/openai-compatible", provider.packageName)
        assertEquals("http://project", provider.baseUrl)
        assertEquals(listOf("GLM-5.2"), provider.models)
        assertEquals(ConfigScopeDto.PROJECT, provider.scope)
    }

    @Test
    fun providerOnlyPresentInServerCatalogHasNoScope() {
        val live = listOf(json("""{"id":"opencode","name":"内置"}"""))

        val provider = SettingsMapping.providers(JsonObject(), JsonObject(), live, emptyList()).single()

        assertEquals("opencode", provider.id)
        assertEquals("内置", provider.name)
        assertNull(provider.scope)
        assertFalse(provider.hasCredential)
    }

    @Test
    fun providerCredentialFlagFollowsIntegrations() {
        val global = json("""{"providers":{"hello-tw":{}}}""")
        val integrations = listOf(
            json("""{"id":"hello-tw","connections":[{"type":"credential","id":"cred-1","label":"API key"}]}"""),
            json("""{"id":"local","connections":[]}""")
        )

        val providers = SettingsMapping.providers(global, JsonObject(), emptyList(), integrations)

        assertTrue(providers.single { it.id == "hello-tw" }.hasCredential)
        assertEquals("hello-tw", providers.single { it.id == "hello-tw" }.integrationId)
    }

    @Test
    fun mcpSupportsV1EnabledAndV2DisabledWritings() {
        val global = json(
            """
            {"mcp":{"servers":{
              "v1-on":{"type":"local","command":["npx","a"],"enabled":true,"environment":{"K":"V"}},
              "v1-off":{"type":"local","command":["npx","b"],"enabled":false}
            }}}
            """.trimIndent()
        )
        val project = json("""{"mcp":{"servers":{"v2-off":{"type":"remote","url":"https://x","disabled":true}}}}""")

        val servers = SettingsMapping.mcpServers(global, project, emptyList())

        assertTrue(servers.single { it.name == "v1-on" }.enabled)
        assertEquals(listOf("npx", "a"), servers.single { it.name == "v1-on" }.command)
        assertEquals(mapOf("K" to "V"), servers.single { it.name == "v1-on" }.environment)
        assertFalse(servers.single { it.name == "v1-off" }.enabled)
        assertFalse(servers.single { it.name == "v2-off" }.enabled)
        assertEquals("https://x", servers.single { it.name == "v2-off" }.url)
    }

    @Test
    fun mcpMergesLiveStatusAndKeepsProjectPriority() {
        val global = json("""{"mcp":{"servers":{"codegraph":{"type":"local","command":["global"]}}}}""")
        val project = json("""{"mcp":{"servers":{"codegraph":{"type":"local","command":["project"]}}}}""")
        val live = listOf(
            json("""{"name":"codegraph","status":{"status":"connected"}}"""),
            json("""{"name":"only-live","status":{"status":"failed","error":"boom"}}""")
        )

        val servers = SettingsMapping.mcpServers(global, project, live)

        val codegraph = servers.single { it.name == "codegraph" }
        assertEquals(listOf("project"), codegraph.command)
        assertEquals(ConfigScopeDto.PROJECT, codegraph.scope)
        assertEquals("connected", codegraph.status)

        val onlyLive = servers.single { it.name == "only-live" }
        assertEquals("failed", onlyLive.status)
        assertEquals("boom", onlyLive.statusError)
        assertNull(onlyLive.scope)
    }

    @Test
    fun mcpTimeoutPrefersProjectAndNullWhenAbsent() {
        val global = json("""{"mcp":{"timeout":{"startup":1000,"execution":2000}}}""")
        val project = json("""{"mcp":{"timeout":{"startup":3000}}}""")

        val timeout = SettingsMapping.mcpTimeout(project, global)!!
        assertEquals(3000L, timeout.startup)
        assertNull(timeout.catalog)
        assertEquals(2000L, timeout.execution)

        assertNull(SettingsMapping.mcpTimeout(JsonObject(), JsonObject()))
    }

    @Test
    fun enabledDefaultsToTrueWhenUnspecified() {
        assertTrue(SettingsMapping.isEnabled(null))
        assertTrue(SettingsMapping.isEnabled(JsonObject()))
        assertFalse(SettingsMapping.isEnabled(json("""{"disabled":true}""")))
        assertFalse(SettingsMapping.isEnabled(json("""{"enabled":false}""")))
    }
}
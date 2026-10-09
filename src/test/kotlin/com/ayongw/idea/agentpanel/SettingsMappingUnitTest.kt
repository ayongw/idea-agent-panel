package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.SettingsMapping
import com.ayongw.idea.agentpanel.shared.ConfigScopeDto
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

        val providers = SettingsMapping.providers(global, project, emptyList(), emptyList(), emptyList())

        val provider = providers.single()
        assertEquals("hello-tw", provider.id)
        assertEquals("两轮项目", provider.name)
        assertEquals("aisdk:@ai-sdk/openai-compatible", provider.packageName)
        assertEquals("http://project", provider.baseUrl)
        // 模型为两级配置的并集（与 opencode 的深合并一致）
        assertEquals(listOf("GLM-5.2", "old"), provider.models.map { it.id })
        assertTrue(provider.custom)
        assertEquals(ConfigScopeDto.PROJECT, provider.scope)
    }

    @Test
    fun providerOnlyPresentInServerCatalogHasNoScope() {
        val live = listOf(json("""{"id":"opencode","name":"内置"}"""))

        val provider = SettingsMapping.providers(JsonObject(), JsonObject(), live, emptyList(), emptyList()).single()

        assertEquals("opencode", provider.id)
        assertEquals("内置", provider.name)
        assertNull(provider.scope)
        assertFalse(provider.custom)
        assertFalse(provider.hasCredential)
    }

    @Test
    fun providerCredentialFlagFollowsIntegrations() {
        val global = json("""{"providers":{"hello-tw":{}}}""")
        val integrations = listOf(
            json("""{"id":"hello-tw","connections":[{"type":"credential","id":"cred-1","label":"API key"}]}"""),
            json("""{"id":"local","connections":[]}""")
        )

        val providers = SettingsMapping.providers(global, JsonObject(), emptyList(), emptyList(), integrations)

        assertTrue(providers.single { it.id == "hello-tw" }.hasCredential)
        assertEquals("hello-tw", providers.single { it.id == "hello-tw" }.integrationId)
    }

    @Test
    fun providerModelsMergeConfigWithEnabledCatalog() {
        // 配置里声明了「已启用 + 已禁用」两个模型，服务端只返回启用的那个
        val global = json(
            """
            {"providers":{"hello-tw":{"models":{
              "GLM-5.2":{"name":"配置名"},
              "deprecated":{"disabled":true}
            }}}}
            """.trimIndent()
        )
        val live = listOf(json("""{"id":"GLM-5.2","modelID":"GLM-5.2","providerID":"hello-tw","name":"服务端名"}"""))

        val models = SettingsMapping.providers(global, JsonObject(), emptyList(), live, emptyList())
            .single().models

        // 被禁用的模型服务端不返回，只能靠配置补齐，否则界面上会消失、无法再启用
        assertEquals(listOf("GLM-5.2", "deprecated"), models.map { it.id })
        val enabled = models.single { it.id == "GLM-5.2" }
        assertFalse(enabled.disabled)
        assertTrue(enabled.declaredInConfig)
        assertEquals("配置名", enabled.name)
        val disabled = models.single { it.id == "deprecated" }
        assertTrue(disabled.disabled)
        assertTrue(disabled.declaredInConfig)
    }

    @Test
    fun disabledModelWithoutNameFallsBackToIdDerivedName() {
        // 服务端只返回启用模型，被禁模型的名字只能来自配置；配置里也没写 name 时按 id 兜底，列表不出现空名称
        val global = json(
            """
            {"providers":{"github-copilot":{"models":{
              "claude-opus-4.7":{"disabled":true},
              "claude-opus-5":{"name":"Claude Opus 5","disabled":true}
            }}}}
            """.trimIndent()
        )
        val live = listOf(json("""{"id":"claude-opus-4.8","providerID":"github-copilot","name":"Claude Opus 4.8"}"""))

        val models = SettingsMapping.providers(global, JsonObject(), emptyList(), live, emptyList())
            .single().models

        assertEquals(listOf("claude-opus-4.7", "claude-opus-4.8", "claude-opus-5"), models.map { it.id })
        assertEquals("Claude Opus 4.7", models.single { it.id == "claude-opus-4.7" }.name)
        assertEquals("Claude Opus 4.8", models.single { it.id == "claude-opus-4.8" }.name)
        assertEquals("配置里声明过的名称优先", "Claude Opus 5", models.single { it.id == "claude-opus-5" }.name)
    }

    @Test
    fun providerModelsWithoutConfigDeclarationCannotBeRemoved() {
        val global = json("""{"providers":{"opencode":{}}}""")
        val live = listOf(json("""{"id":"gpt-5","providerID":"opencode","name":"GPT-5"}"""))

        val model = SettingsMapping.providers(global, JsonObject(), emptyList(), live, emptyList())
            .single().models.single()

        assertEquals("gpt-5", model.id)
        assertFalse("仅服务端目录带来的模型不能从配置里删除", model.declaredInConfig)
        assertFalse(model.disabled)
    }

    @Test
    fun supportsLegacySingleProviderWriting() {
        // V1 写法：provider（单数）+ npm + options.baseURL，模型用 status=deprecated 表达禁用
        val global = json(
            """
            {"provider":{"hello-tw":{
              "name":"两轮",
              "npm":"@ai-sdk/openai-compatible",
              "options":{"baseURL":"https://pre-tokens.hellobike.cn/v1"},
              "models":{"GLM-5.2":{"name":"GLM-5.2"},"old":{"name":"旧模型","status":"deprecated"}}
            }}}
            """.trimIndent()
        )
        val live = listOf(json("""{"id":"GLM-5.2","providerID":"hello-tw","name":"GLM-5.2"}"""))

        val provider = SettingsMapping.providers(global, JsonObject(), emptyList(), live, emptyList()).single()

        assertEquals("hello-tw", provider.id)
        assertEquals("两轮", provider.name)
        // 包名统一成 V2 的 aisdk: 形式展示
        assertEquals("aisdk:@ai-sdk/openai-compatible", provider.packageName)
        assertEquals("https://pre-tokens.hellobike.cn/v1", provider.baseUrl)
        assertEquals(ConfigScopeDto.GLOBAL, provider.scope)
        assertTrue(provider.custom)
        assertEquals(listOf("GLM-5.2", "old"), provider.models.map { it.id })
        assertFalse(provider.models.single { it.id == "GLM-5.2" }.disabled)
        assertTrue("V1 的 status=deprecated 即禁用", provider.models.single { it.id == "old" }.disabled)
        assertTrue(provider.models.single { it.id == "old" }.declaredInConfig)
    }

    @Test
    fun legacyModelWithoutDisabledKeyStaysEnabled() {
        // V1 模型里出现 V2 的 disabled 字段也应能识别
        val global = json("""{"provider":{"p":{"models":{"m":{"disabled":true}}}}}""")

        val model = SettingsMapping.providers(global, JsonObject(), emptyList(), emptyList(), emptyList())
            .single().models.single()

        assertTrue(model.disabled)
    }

    @Test
    fun v2ProviderOverridesLegacyWithSameId() {
        // normalize 里 V2 条目整体覆盖同名 V1 条目
        val global = json(
            """
            {"provider":{"hello-tw":{"name":"旧名","npm":"@ai-sdk/openai-compatible"}},
             "providers":{"hello-tw":{"name":"新名","package":"aisdk:@ai-sdk/openai-compatible"}}}
            """.trimIndent()
        )

        val provider = SettingsMapping.providers(global, JsonObject(), emptyList(), emptyList(), emptyList()).single()

        assertEquals("新名", provider.name)
    }

    @Test
    fun writeTargetFollowsDeclaredContainer() {
        assertEquals(
            "provider",
            SettingsMapping.providerWriteTarget(json("""{"provider":{"hello-tw":{"npm":"x"}}}"""), "hello-tw").container
        )
        assertEquals(
            "providers",
            SettingsMapping.providerWriteTarget(
                json("""{"provider":{"hello-tw":{}},"providers":{"hello-tw":{}}}"""),
                "hello-tw"
            ).container
        )
        assertEquals(
            "未声明过按 V2 新建",
            "providers",
            SettingsMapping.providerWriteTarget(JsonObject(), "brand-new").container
        )
    }

    @Test
    fun legacyProviderIdRenamesAreAppliedAndWriteBackToOriginalKey() {
        val config = json("""{"provider":{"azure-cognitive-services":{"npm":"@ai-sdk/azure"}}}""")

        assertEquals(setOf("azure"), SettingsMapping.providerConfigs(config).keys)
        // 写回必须用文件里的原名，否则会新建出一条重复条目
        val target = SettingsMapping.providerWriteTarget(config, "azure")
        assertEquals("provider", target.container)
        assertEquals("azure-cognitive-services", target.key)
    }

    @Test
    fun skillPathsSupportsArrayAndObjectWritings() {
        // opencode 兼容字符串数组与 {paths, urls} 对象两种写法，只读一种会漏展示
        val global = json("""{"skills":{"paths":["~/.kiro/skills"],"urls":["https://skills.example/index.json"]}}""")
        val project = json("""{"skills":["/tmp/project-skills"]}""")

        assertEquals(
            listOf("~/.kiro/skills", "https://skills.example/index.json", "/tmp/project-skills"),
            SettingsMapping.skillPaths(global, project)
        )
    }

    @Test
    fun skillPathsEmptyWhenSkillsAbsentOrOfOtherType() {
        assertEquals(emptyList<String>(), SettingsMapping.skillPaths(JsonObject(), JsonObject()))
        assertEquals(emptyList<String>(), SettingsMapping.skillPaths(json("""{"skills":"nope"}"""), JsonObject()))
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
    fun mcpReadsLegacyFlatEntriesAndSkipsReservedKeys() {
        val global = json(
            """
            {"mcp":{
              "flat-local":{"type":"local","command":["/x/flat","proxy"],"environment":{"K":"V"},"cwd":"/tmp/wd"},
              "timeout":{"startup":1000},
              "servers":{"native-only":{"type":"local","command":["n"]}}
            }}
            """.trimIndent()
        )

        val servers = SettingsMapping.mcpServers(global, JsonObject(), emptyList())

        assertEquals(listOf("flat-local", "native-only"), servers.map { it.name })
        val flat = servers.single { it.name == "flat-local" }
        assertEquals(listOf("/x/flat", "proxy"), flat.command)
        assertEquals(mapOf("K" to "V"), flat.environment)
        assertEquals("/tmp/wd", flat.cwd)
        assertEquals(ConfigScopeDto.GLOBAL, flat.scope)
        // 保留键 `timeout` 不应被当作服务器名
        assertTrue(servers.none { it.name == "timeout" })
    }

    @Test
    fun mcpNativeEntryWinsOverLegacyFlatWithSameName() {
        val global = json(
            """
            {"mcp":{
              "servers":{"dup":{"type":"local","command":["native"]}},
              "dup":{"type":"local","command":["flat"]}
            }}
            """.trimIndent()
        )

        val server = SettingsMapping.mcpServers(global, JsonObject(), emptyList()).single()

        assertEquals("dup", server.name)
        assertEquals(listOf("native"), server.command)
        assertEquals(ConfigScopeDto.GLOBAL, server.scope)
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
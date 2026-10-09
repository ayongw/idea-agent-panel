package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.settings.AgentConnectionSettings
import com.ayongw.idea.agentpanel.frontend.settings.AgentSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置结构的多 agent 预留（抽出 [AgentConnectionSettings] 作为未来的分组单元）。
 *
 * 本轮只做**结构抽取**，对外行为无变化：便捷属性与分组字段必须始终一致，
 * 否则读取方（设置页 / ConnectionManager / 后端设置 RPC）会读到过期值。
 */
class AgentSettingsGroupingUnitTest {

    @Test
    fun 连接配置的默认值不变() {
        val c = AgentConnectionSettings()
        assertEquals("http://127.0.0.1:4096", c.serverUrl)
        assertEquals("opencode", c.username)
        assertEquals(true, c.autoStartServer)
        assertEquals(true, c.reuseExternalServer)
        assertEquals("", c.cliPath)
    }

    @Test
    fun 便捷属性读写落到分组字段() {
        val s = AgentSettingsState()
        s.serverUrl = "http://10.0.0.1:9999"
        s.username = "alice"
        s.autoStartServer = false
        s.reuseExternalServer = false
        s.cliPath = "/opt/opencode/bin/opencode"

        assertEquals("http://10.0.0.1:9999", s.opencode.serverUrl)
        assertEquals("alice", s.opencode.username)
        assertEquals(false, s.opencode.autoStartServer)
        assertEquals(false, s.opencode.reuseExternalServer)
        assertEquals("/opt/opencode/bin/opencode", s.opencode.cliPath)
    }

    @Test
    fun 直接改分组字段也能被便捷属性读到() {
        // 反向：接第二个 agent 时会直接往分组里写，这里保证读到的是最新值而非快照
        val s = AgentSettingsState()
        s.opencode.serverUrl = "http://127.0.0.1:4097"
        s.opencode.autoStartServer = false

        assertEquals("http://127.0.0.1:4097", s.serverUrl)
        assertEquals(false, s.autoStartServer)
    }

    @Test
    fun 分组字段为null时便捷属性回落到默认值() {
        // XML 缺字段时委托属性为 null（BaseState 语义），便捷属性必须给出可用默认值，
        // 否则设置页显示空白输入框、后端拿到空 URL
        val s = AgentSettingsState()
        s.opencode.serverUrl = null
        s.opencode.username = null
        s.opencode.cliPath = null

        assertEquals(AgentConnectionSettings.DEFAULT_SERVER_URL, s.serverUrl)
        assertEquals(AgentConnectionSettings.DEFAULT_USERNAME, s.username)
        assertEquals("", s.cliPath)
    }

    @Test
    fun loadState搬运分组对象而非逐字段拷贝() {
        // 未来结构变成 agents Map 时这里只需改一行（搬 Map），逐字段拷贝的写法会漏搬
        val saved = AgentSettingsState().apply {
            opencode = AgentConnectionSettings().apply {
                serverUrl = "http://saved:4096"
                cliPath = "/saved/cli"
            }
        }
        val target = AgentSettingsState()

        target.loadState(saved)

        assertEquals("http://saved:4096", target.serverUrl)
        assertEquals("/saved/cli", target.cliPath)
        assertEquals(saved.opencode, target.opencode)
    }

    @Test
    fun 密码不进持久化结构() {
        // 凭据走 PasswordSafe（OpenCodePasswordStore），连接配置里不能出现密码字段，
        // 否则明文会随 opencode-settings.xml 落盘
        val declared = AgentConnectionSettings::class.java.declaredFields.map { it.name.lowercase() }
        assertEquals("连接配置里不应有密码字段", emptyList<String>(), declared.filter { it.contains("password") })
        assertNull(
            "不应出现 secret/credential 形式的凭据字段",
            declared.firstOrNull { it.contains("secret") || it.contains("credential") }
        )
    }
}
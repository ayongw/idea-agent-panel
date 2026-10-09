package com.ayongw.idea.agentpanel.frontend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 单个 agent 的连接配置（**尚未**按 agent 分组持久化，分组方案的演进路径见 [AgentSettingsState]）。
 *
 * 为什么先抽成独立数据类、而不是把字段继续平铺在 [AgentSettingsState] 里：
 * 接入第二个 agent 时连接配置需要「按 agentId 分组并存」，本类即为那个分组单元。
 * 届时只需把 [AgentSettingsState.opencode] 换成 `agents: Map<String, AgentConnectionSettings>`
 * （key = agentId），本类及其全部读取方（设置页 / ConnectionManager / 后端设置 RPC）无需改动。
 *
 * 字段取舍原则：**只放「不同 agent 大概率都有」的连接语义**（地址 / 凭据 / 拉起策略 / CLI 覆盖）。
 * agent 独有的能力开关（MCP、权限确认…）不要往这里塞——那属于该 agent 自己的适配层配置。
 *
 * 密码不在此持久化（明文凭据会被 IDE 判为敏感信息），见 [OpenCodePasswordStore]。
 */
class AgentConnectionSettings : BaseState() {

    /** Server 地址，如 http://127.0.0.1:4096（opencode serve 默认端口 4096） */
    var serverUrl: String? by string(DEFAULT_SERVER_URL)

    /** Basic 认证用户名，opencode serve 默认 opencode */
    var username: String? by string(DEFAULT_USERNAME)

    /** 是否允许插件自动拉起 server（默认开，见 TSD-31 §13 决策点 1） */
    var autoStartServer: Boolean by property(true)

    /** 是否允许复用非本插件启动的实例（含经密钥接入，见 TSD-31 §13 决策点 2） */
    var reuseExternalServer: Boolean by property(true)

    /** opencode CLI 路径覆盖；空 = 从 PATH 解析（CLI 缺失时的兜底入口） */
    var cliPath: String? by string("")

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_USERNAME = "opencode"
    }
}

/**
 * 插件设置（应用级）。
 *
 * ============================ 多 agent 配置演进（预留说明） ============================
 * 插件定位已是多 agent 面板（当前仅接 OpenCode，后续接其它 agent 含自研 agent）。
 * 接入第二个 agent 时的目标结构：
 * ```
 * AgentSettingsState
 * ├── common: CommonSettings                       // 跨 agent 的插件级项（主题、行为开关…）
 * └── agents: Map<String, AgentConnectionSettings>  // agentId → 该 agent 的连接配置
 *     ├── "opencode" → AgentConnectionSettings
 *     └── "<新 agent>" → AgentConnectionSettings
 * ```
 *
 * 届时的两笔改动（本轮**刻意不做**，避免在只有一种实现时锁定方案）：
 * 1. **数据结构**：`opencode` 单字段换成 `agents` Map。IntelliJ 的 XML 序列化对
 *    `Map<String, Bean>` 支持有限（需 `@MapAnnotation` 或自定义 serializer），
 *    现在就换等于一次性锁定该方案，而第二个 agent 需要哪些字段仍未知，结构可能再改一次。
 * 2. **旧数据迁移**：本文件现有的扁平字段（`serverUrl` 等）会下移一层，XML 里读不回来，
 *    需要在 `loadState` 里做一次兼容（读旧扁平字段 → 填入 `agents["opencode"]`）。
 *    本轮不动字段名，正是为了把迁移成本压到一次、且等到那时字段已稳定。
 *
 * 保持不变的持久化契约：`@State(name = "OpenCodeSettings")` 与
 * `Storage("opencode-settings.xml")`。name 即落盘文件名，属**持久化契约（跨版本不改）**，
 * 它标识的是「OpenCode 连接配置」这份数据，与插件名无关——真正的插件级标识
 * （plugin id / rootProject.name / 包名 / 展示层文案）已在 2026-10-08 完成改名。
 */
@State(
    name = "OpenCodeSettings",
    storages = [Storage("opencode-settings.xml")]
)
class AgentSettingsState : PersistentStateComponent<AgentSettingsState> {

/**
 * OpenCode 的连接配置（当前唯一 agent；未来将移入按 agentId 分组的 Map）
 *
 * 用普通属性而非 `by property(...)`：后者是 [BaseState] 的成员委托，只在 `BaseState` 子类内可用，
 * 而本类是 [PersistentStateComponent]。嵌套 bean 的序列化由 XmlSerializer 走
 * getter/setter 完成（[AgentConnectionSettings] 的 `by string()/property()` 正好暴露了它们）。
 */
var opencode: AgentConnectionSettings = AgentConnectionSettings()

    /** 读侧便捷访问：等价于 `opencode.serverUrl`（XML 缺字段时回落到默认值） */
    var serverUrl: String
        get() = opencode.serverUrl ?: AgentConnectionSettings.DEFAULT_SERVER_URL
        set(value) {
            opencode.serverUrl = value
        }

    /** 读侧便捷访问：等价于 `opencode.username` */
    var username: String
        get() = opencode.username ?: AgentConnectionSettings.DEFAULT_USERNAME
        set(value) {
            opencode.username = value
        }

    /** 读侧便捷访问：等价于 `opencode.autoStartServer` */
    var autoStartServer: Boolean
        get() = opencode.autoStartServer
        set(value) {
            opencode.autoStartServer = value
        }

    /** 读侧便捷访问：等价于 `opencode.reuseExternalServer` */
    var reuseExternalServer: Boolean
        get() = opencode.reuseExternalServer
        set(value) {
            opencode.reuseExternalServer = value
        }

    /** 读侧便捷访问：等价于 `opencode.cliPath` */
    var cliPath: String
        get() = opencode.cliPath.orEmpty()
        set(value) {
            opencode.cliPath = value
        }

    override fun getState(): AgentSettingsState = this

    override fun loadState(state: AgentSettingsState) {
        opencode = state.opencode
    }

    companion object {
        const val DEFAULT_SERVER_URL = AgentConnectionSettings.DEFAULT_SERVER_URL
        const val DEFAULT_USERNAME = AgentConnectionSettings.DEFAULT_USERNAME

        fun getInstance(): AgentSettingsState {
            return ApplicationManager.getApplication().getService(AgentSettingsState::class.java)
        }
    }
}
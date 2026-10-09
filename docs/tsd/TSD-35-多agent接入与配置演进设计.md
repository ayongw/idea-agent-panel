# TSD-35 多 agent 接入与配置演进设计

> 状态：**规划中**（2026-10-08 立项，当前只有 OpenCode 一个实现）
> 范围：插件从「OpenCode 面板」演进为「多 agent 面板」时的接入路径与预留
> 前提阅读：先看 `opencode-backend/.../backend/agent/AGENT_LAYER.md`（代码侧的隔离边界）

---

## 一、为什么要提前设计

插件定位已从「OpenCode 面板」改为「多 agent 面板」：当前接 OpenCode，后续接其它 agent（含自研 agent）。
如果不做预留，第二个 agent 接入时会被迫在功能改动中夹带大范围重构，回归时分不清是重构问题还是新功能问题。

**但不做什么**（关键决策）：**不在只有一个实现时造 agent SPI / 工厂 / 注册表**。
没有第二个实现来验证抽象是否正确，过早抽象通常要推倒重来。接口设计留到第二个 agent 真正接入时再做，
届时才有真实需求可依据。

本轮只做两类预留：**纯数据抽取**（与 UI 无关、不会返工）+ **明确的演进路径书面化**。

---

## 二、当前形态（2026-10-08）

```
opencode-shared/            中立契约：ChatMessage / ChatRepositoryRpcApi / ToolCallDto
opencode-frontend/
  chatApp/ui/**             Swing 组件（agent 无关）
  chatApp/viewmodel/**      ViewModel（依赖 ChatRepositoryApi 接口）
  settings/ statusBar/ toolWindow/ vcs/   功能名目录，内部当前填的是 opencode 实现
opencode-backend/
  BackendChatRepositoryModel.kt          门面（agent 无关的外壳）
  agent/opencode/{event,mcp,repository,server}   实现
```

配置结构（`AgentSettingsState`，应用级）：

```kotlin
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml")])
class AgentSettingsState : PersistentStateComponent<AgentSettingsState> {
    var opencode: AgentConnectionSettings = AgentConnectionSettings()  // ← 唯一的分组
    // 便捷属性：serverUrl / username / autoStartServer / reuseExternalServer / cliPath
}
```

---

## 三、目标形态

### 3.1 配置结构

```kotlin
class AgentSettingsState : PersistentStateComponent<AgentSettingsState> {
    /** 跨 agent 的插件级项（主题、行为开关…）；当前无内容，为将来预留 */
    var common: CommonSettings = CommonSettings()

    /** agentId → 该 agent 的连接配置；key 约定用小写 agent 名（"opencode"） */
    var agents: Map<String, AgentConnectionSettings> = mapOf(OPENCODE to AgentConnectionSettings())

    fun agent(id: String): AgentConnectionSettings = agents.getOrPut(id) { AgentConnectionSettings() }
}
```

`AgentConnectionSettings` **已在本轮抽出**（见 §五），字段只放「不同 agent 大概率都有」的连接语义：

| 字段 | 语义 | 备注 |
|---|---|---|
| `serverUrl` | 服务地址 | agent 的 HTTP/RPC 端点 |
| `username` | 认证用户名 | 多数 agent 用 |
| `autoStartServer` | 允许插件自动拉起 | 无本进程概念的 agent 可忽略 |
| `reuseExternalServer` | 允许复用外部实例 | 同上 |
| `cliPath` | CLI 路径覆盖 | 仅 CLI 型 agent 用，其余留空 |

**不放进这里的**：agent 独有能力开关（MCP 服务器清单、权限确认策略、模型偏好…）——
那些属于该 agent 自己的适配层配置，放进来会让通用结构被第一个 agent 的特性污染。

### 3.2 设置页 UI

目标形态是 **agent 切换器 + 各自的 tab 组**：

```
Connection | Models | Rules | Skills | MCP        ← 当前：opencode 的能力清单平铺在顶层
        ↓
[ OpenCode ▾ ]                                   ← agent 切换器（第二个 agent 出现时引入）
  ├─ Connection | Models | Rules | Skills | MCP   ← opencode 的 tab 组
  └─ （第二个 agent 的 tab 组）
```

**本轮刻意不给 Connection tab 加「OpenCode」分组标题** —— 引入切换器后那行标题必然要删，
属于给自己埋需回滚的代码。过渡期保持现状。

### 3.3 包结构

```
opencode-backend/agent/
├── AGENT_LAYER.md
└── opencode/            ← 已有
    └── <新 agent>/      ← 新增时并列，结构对称
```

frontend 侧暂不动（`settings/` `statusBar/` 等是功能名目录），新 agent 的专属装配放
`frontend/agent/<name>/`，与 `chatApp/ui`（共享 UI）分离。

---

## 四、接入一个新 agent 的 checklist

1. **协议客户端**：`backend/agent/<name>/` 下实现该 agent 的 REST / 流式客户端
2. **协议翻译**：在该包内把它的原生消息结构翻译成 `opencode-shared` 的中立模型
   （参照 `opencode/repository/MessageMapper.kt`）；**中立模型不够用时**，评估是扩展既有 DTO
   还是新增 DTO——不要把 agent 专属字段塞进 `ChatMessage`
3. **能力差异**：
   - 无 MCP / 无用量 / 无权限确认 → 在 `shared` 下新增独立接口，不要在既有接口里塞可选参数
   - 连接配置字段不同 → 扩 `AgentConnectionSettings`（仅当多数 agent 都需要时）或放该 agent 自己的配置类
4. **门面分发**：`BackendChatRepositoryModel` 按 agentId 分发（选择器逻辑在那一刻才写）
5. **设置页**：agent 切换器 + 该 agent 的 tab 组
6. **状态栏**：状态源改为「当前 agent 的状态」，而不是 opencode serve 的进程状态
7. **文档**：更新本文的「已接入 agent」小节 + `AGENT_LAYER.md`

---

## 五、本轮已做的预留（可回滚性说明）

| 项 | 内容 | 为什么现在做是安全的 |
|---|---|---|
| 数据抽取 | `AgentConnectionSettings` + `AgentSettingsState.opencode` 单字段 + 同名便捷属性 | 纯结构，读取方零改动；未来换 Map 时只动 `AgentSettingsState` 一个文件 |
| 字段命名 | 字段名保持 `serverUrl` / `username` / … 不变 | 保持 XML 兼容——现在改名会把迁移成本从「一次」变成「两次」 |
| 单测 | `AgentSettingsGroupingUnitTest`（6 例） | 锁住便捷属性与分组字段一致性、null 回落、`loadState` 搬分组对象 |
| 书面化 | 本文 + `AgentSettingsState` / `AgentConnectionSettings` 的 KDoc | — |

**刻意留到第二个 agent 接入时**：agents Map 结构、旧扁平字段的 XML 迁移、agent 切换器 UI。

---

## 六、命名约定（接入时必须遵守）

| 对象 | 约定 | 例 |
|---|---|---|
| agentId | 小写短横线，与目录名一致 | `opencode`、`<新 agent>` |
| 包路径 | `backend/agent/<agentId>/`、`frontend/agent/<agentId>/` | `backend/agent/opencode/` |
| 配置分组 key | agentId | `agents["opencode"]` |
| 类名前缀 | **agent 专属实现**用 agent 名（`OpenCodeRestClient`）；**插件级**用 `Agent*`（`AgentChatApp`、`AgentSettingsState`） | — |
| 凭据 | 走 PasswordSafe，`CredentialAttributes(generateServiceName("<agentId>"), account)`，**不进持久化 bean** | `OpenCodePasswordStore` |

---

## 七、风险与未决项

| 项 | 说明 |
|---|---|
| IntelliJ 对 `Map<String, Bean>` 的序列化 | 支持有限，需 `@MapAnnotation` 或自定义 serializer。届时需实测并写单测兜底 |
| 旧数据迁移 | 扁平字段下移一层后 XML 读不回来，需在 `loadState` 兼容。届时需单测覆盖「旧 XML → 新结构」 |
| `opencode-settings.xml` 文件名 | 保持 `OpenCodeSettings`（持久化契约）。若将来 agent 数量增长到需要改名，应写一次性迁移而非直接改 |
| 状态栏语义 | 现在展示的是 opencode serve 进程状态；多 agent 下应改为「当前 agent 状态」，需要中立的状态模型 |
| 设置页 tab 数量 | 5 个 tab 全属 opencode；第二个 agent 接入后平铺会失控，切换器是必需的（不是可选优化） |

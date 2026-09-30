# TSD-32-MCP工具清单展示设计

> 适用范围：opencode-idea-panel **设置页 MCP 面板**——在每张 MCP 服务器卡片下展示该服务器提供的**工具清单**（工具名 + 描述，可展开/收起），并把工具数并入状态徽章。
> 关联文档：设置页整体与卡片组件见《TSD-05-设置管理设计》；进程与连接管理纪律见《TSD-31-进程与连接管理方案》；供应商/模型表格（同页相邻区块）见《TSD-05》。
> 契约依据：本机 opencode v2.0.18 实测（`127.0.0.1:4096`、ITest 实例 `4097` 活体 `openapi.json` 均为 **115 个端点**；MCP 相关端点共 5 个，见 §2）。工具清单可行性由 PoC 脚本 [temp/opencode-mcp-probe/mcp_list_tools.py](../../temp/opencode-mcp-probe/mcp_list_tools.py) 验证。

## 修订历史
| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-09-30 | 初版：确认 opencode 无「按 MCP 列工具」接口（候选端点全 404），确定由**插件自建 MCP 客户端**（stdio / Streamable HTTP）拉 `tools/list`；给出配置读取兼容修复（P0）、RPC 与前端渲染规格、失败降级矩阵、S1–S4 实施阶段 | agent |

## 1. 结论与总览

需求要的效果（Trae 式）是：MCP 卡片标题行显示 `Connected (N tools)`，展开后逐条列出**工具名 + 描述**。经实测，**opencode HTTP API 不提供该数据**（§2），因此本方案由**插件自身作为 MCP 客户端**，在用户展开某张卡片时按需向该 MCP 服务器发起一次最小 MCP 会话（`initialize → notifications/initialized → tools/list`），拿到工具清单与描述后渲染；结果按服务器缓存，避免重复起进程。

```
┌──────────────────────────────────────────────────────────────────────┐
│ MCP 服务器                         [过滤…]        [打开配置文件]      │
├──────────────────────────────────────────────────────────────────────┤
│ ▸ codegraph          Connected (1 tool)        [ ]开关   [齿轮]      │
│ ▾ simple-mem         Connected (6 tools)       [ ]开关   [齿轮]      │
│     memory_search    检索记忆。query: 围绕同一主题列 2-6 个相关词…    │
│     memory_get       获取单条记忆完整内容，并登记一次使用（heat+1）…  │
│     memory_write     写入记忆。simple-mem 不生成记忆语义；调用方…     │
│     memory_delete    永久删除记忆，不可恢复。保留历史用 replace_id…   │
│     memory_list      列出记忆，返回列表+可选统计摘要。                │
│     memory_catalog   记忆全景：返回 active 记忆覆盖的作用域…          │
│ ▸ mintlify-index     Connected                  [ ]开关   [齿轮]      │
└──────────────────────────────────────────────────────────────────────┘
```

关键决策：

| # | 决策 | 采用方案 | 理由 |
|---|------|---------|------|
| 1 | 数据来源 | **插件自建 MCP 客户端**（local=stdio，remote=Streamable HTTP） | opencode 无对应接口；且只有自己连才能拿到**描述**（§2 已探测确认） |
| 2 | 拉取时机 | **展开时懒加载**（单飞 + 缓存） | 打开设置页不产生额外进程/请求；避免一次拉起全部 MCP 进程 |
| 3 | 支持范围 | local + remote 都支持 | remote（`mintlify-index` 等）同样能 `tools/list`，失败时按 §6 显示原因 |
| 4 | 展示字段 | 仅 `name` + `description` | 与截图一致；`inputSchema` 不在本期展示范围（§9） |
| 5 | 工具数位置 | 状态徽章追加 `(N tools)`，**首次展开后**出现 | 懒加载决定计数只能来自已拉取结果；缓存命中后收起态也保留 |
| 6 | 配置读取 | 先修 P0：兼容 legacy 扁平 `mcp.<name>`，并读 `cwd` | 现状只读 `mcp.servers.<name>`，而本机配置是扁平形式 → 读不到 `command` 就拉不了工具（§3.4） |
| 7 | 进程生命周期 | 每次拉取起一个短命进程，超时即 `destroyForcibly()` | 与 TSD-31 同源纪律：不留残留进程 |
| 8 | 与 opencode 的关系 | 工具清单与 opencode 连接状态**解耦** | 徽章状态仍取 `GET /api/mcp`（opencode 视角）；工具清单取插件自身连接（MCP 服务器视角） |

## 2. 接口验证结论（先证据，后方案）

### 2.1 opencode 侧没有「按 MCP 列工具」的接口

| 探测项 | 结果 |
|--------|------|
| 活体 `GET /openapi.json`（4097） | 115 个端点（与离线导出快照一致），MCP 相关**仅 5 个**：`GET /api/mcp`、`GET /api/mcp/resource`、`POST /api/experimental/mcp/{server}/connect`、`POST .../disconnect`、`PUT/DELETE /api/experimental/mcp/{server}` |
| `GET /api/tool`、`GET /api/tool/ids` | **404** |
| `GET /api/mcp/{server}/tools`、`GET /api/experimental/mcp/{server}/tools` | **404** |
| `Mcp.Status.*` schema | 仅 `{ status }`（`connected` / `pending` / `disabled` / `failed` / `needs_auth`），**无工具数、无工具清单** |
| `GET /api/mcp/resource` | 200，但返回的是 MCP **资源目录**（`Mcp.ResourceCatalog{resources, templates}`），不是工具 |
| `SessionStats.ToolUsage` | 仅 `{name, calls, succeeded, failed, unfinished}`：是"**用过**哪些工具"的统计，无语义描述、且依赖会话历史，不能作为清单来源 |

结论：opencode 只是 MCP 客户端，工具清单未外抛。要展示工具，必须由插件自己连 MCP。

### 2.2 PoC 验证（可行性已坐实）

`initialize → notifications/initialized → tools/list` 三步握手实测（本机 `~/.config/opencode/opencode.jsonc` 里的两个 local server）：

| 服务器 | 命令 | 结果 |
|--------|------|------|
| `codegraph` | `codegraph serve --mcp` | initialize 返回 `serverInfo{name:"codegraph",version:"1.6.0"}`；**1 个工具**：`codegraph_explore` |
| `simple-mem` | `~/.local/bin/simple-mem proxy`（env `MEMORY_DIR=~/memory`） | initialize 返回 `serverInfo{name:"simple-mem"}`；**6 个工具**：`memory_search`/`memory_get`/`memory_write`/`memory_delete`/`memory_list`/`memory_catalog`，均带描述 |

工具数与工具名与需求截图完全一致 —— 该截图即由同类做法产出。

## 3. 数据获取方案

### 3.1 传输分流

| 配置 `type` | 传输 | 要点 |
|-------------|------|------|
| `local` | stdio（子进程） | 用配置的 `command` + `environment`（+ `cwd`）起进程；**换行分隔的 JSON-RPC** 报文 |
| `remote` | Streamable HTTP | 向配置的 `url` 发 POST；响应可能是 `application/json`，也可能是 `text/event-stream`（需按 `data:` 行解析） |

### 3.2 stdio 时序

```
插件(McpToolsClient)                 MCP 进程
   │  spawn(command, env, cwd)          │
   │──initialize{protocolVersion,       │
   │   capabilities{}, clientInfo}─────►│
   │◄──result{protocolVersion,          │
   │   capabilities, serverInfo}────────│
   │──notifications/initialized────────►│   （通知，无 id、无响应）
   │──tools/list{}─────────────────────►│
   │◄──result{tools:[{name,           ──│
   │     description, inputSchema}],    │
   │     nextCursor?}───────────────────│
   │  destroyForcibly()                 ✗   结束（超时/完成都强制回收）
```

| 项 | 规则 |
|----|------|
| 协议版本 | 请求 `2025-06-18`（PoC 实测可用）；以 `result.protocolVersion` 为准，不做版本协商重试 |
| 分页 | 若返回 `nextCursor`，带 `cursor` 再请求，**最多 10 页**（防御死循环），超限只保留已得结果并在 `error` 里记「分页超限」 |
| 报文解析 | 按行读 stdout；忽略无法解析为 JSON 的行（部分 server 会打印日志到 stdout）；`id` 匹配响应用于关联（1=initialize，2=tools/list） |
| stderr | 不阻塞读取（独立线程丢弃），**仅保留末尾 ~2KB** 用于失败时报错文案 |
| 超时 | 单服务器总预算 **8 秒**（initialize 3s + tools/list 5s），到点 `destroyForcibly()` |
| I/O | 用 `ProcessBuilder` 重定向三条流，禁用 IDE 的 `OSProcessHandler` 输出接管（短命进程无需注册到 Run 工具窗口） |

### 3.3 remote 时序（Streamable HTTP）

| 项 | 规则 |
|----|------|
| 请求头 | `Content-Type: application/json`、`Accept: application/json, text/event-stream`；配置里的 `headers` 一并附带 |
| 会话头 | 若 initialize 响应带 `Mcp-Session-Id`，后续 `notifications/initialized` 与 `tools/list` 需回带同值 |
| 协议版本头 | 后续请求带 `MCP-Protocol-Version: <协商结果>` |
| 响应解析 | `Content-Type` 含 `text/event-stream` → 逐行取 `data:` 拼 JSON 后解析；否则按 JSON 解析 |
| OAuth | 401/403 时直接判定"需授权"，按 §6 显示原因（本期不做 OAuth 流程） |
| 遗留 SSE 传输 | 若 POST 得到 405/404 且 URL 以 `/sse` 结尾，判定"不支持"，显示原因（本期不实现 2024-11 的 SSE 双端点传输） |

### 3.4 前置修复：MCP 配置读取兼容（P0）

现状 [SettingsMapping.mcpServers](../../opencode-backend/src/main/kotlin/com/ayongw/idea/opencode/backend/SettingsMapping.kt#L212-L246) 只读 `mcp.servers.<name>`；opencode 源码 [normalize.ts](../../../opencode/packages/core/src/config/normalize.ts#L260-L294) 显示：**原生形态是 `mcp.servers.<name>`，同时兼容 V1 扁平形态 `mcp.<name>`**（本机 `opencode.jsonc` 正是扁平形态）。基线配置字段（源码 `v1/config/mcp.ts`）：`type`、`command`、`cwd`、`environment`、`enabled`、`url`、`headers`、`oauth`、`timeout`。

修复内容：

| 改动 | 说明 |
|------|------|
| 读两层形态 | 先取 `mcp.servers.<name>`，取不到再取 `mcp.<name>`；两者都算「已声明」并参与 `scope` 判定 |
| 新增 `cwd` | `McpServerDto` 增 `cwd: String? = null`，保存路径一并写回（否则起进程的工作目录不确定） |
| legacy 特例 | 跳过 `mcp.servers` / `mcp.timeout` 这两个**键名**被当作服务器名的情形（opencode 自身也是这么区分的） |

不修这一条，local 服务器拿不到 `command`，本方案无法落地。

### 3.5 缓存与并发

| 项 | 规则 |
|----|------|
| 缓存键 | `serverName` + 配置签名（`type|command|url|env|cwd` 的哈希） |
| TTL | 5 分钟；设置页「刷新」与「切换配置作用域」时清空 |
| 单飞 | 同一 server 的并发请求合并为一次（进行中的 `Deferred` 复用） |
| 上限 | 单次会话内最多缓存 32 个 server，超出按 LRU 淘汰 |

## 4. 接口设计（RPC 变更）

```kotlin
// shared/SettingsDtos.kt
@Serializable
data class McpToolDto(val name: String, val description: String? = null)

@Serializable
data class McpToolsDto(
    val serverName: String,
    val tools: List<McpToolDto> = emptyList(),
    /** 失败原因（成功为 null）；供 UI 直接展示，如「进程启动失败: ...」「需 OAuth 授权」 */
    val error: String? = null
)

// shared/SettingsRpcApi.kt（读路径，按需懒加载，不并入 getSnapshot）
suspend fun listMcpTools(projectId: ProjectId, serverName: String): McpToolsDto
```

| 约定 | 说明 |
|------|------|
| 失败不抛异常 | 与 `SettingsSnapshotDto.warnings` 同风格：异常在 backend 捕获后写入 `error` |
| 不并入 `getSnapshot` | 打开设置页不应触发任何 MCP 进程；`getSnapshot` 保持纯 HTTP 读取 |
| 后端实现位置 | 新增 `backend/mcp/McpToolsClient.kt`（协议与进程）+ `backend/mcp/McpToolsCache.kt`（缓存/单飞）；`BackendSettingsRpcApi.listMcpTools` 负责按配置分流并转 DTO |
| 配置来源 | 复用 `SettingsMapping` 读取的配置（§3.4 修复后含 `command`/`url`/`environment`/`cwd`） |

## 5. 前端渲染规格

| 项 | 规则 |
|----|------|
| 卡片结构 | 在 [SettingsCard](../../opencode-frontend/src/main/kotlin/com/ayongw/idea/opencode/frontend/settings/SettingsCards.kt#L113-L159) 增「可展开」能力：标题行左侧加折叠箭头（`AllIcons.General.ArrowRight/ArrowDown`），点击标题行（或箭头）切换；展开区在卡片下方，缩进 12px |
| 展开区内容 | 每行 = 工具名（等宽小字、加粗）+ 描述（`UIUtil.getContextHelpForeground()` 小字、单行截断 `…`、tooltip 显示全文） |
| 行数上限 | 展开区最多显示 10 行，超出在展开区内部滚动（`JBScrollPane`，高度 ~160px），不撑高整个设置页 |
| 状态徽章 | 首次拉取成功后，徽章文案追加 `(N tools)`；`N=1` 用单数 `tool`。未展开过则维持现状（仅状态） |
| 加载态 | 展开瞬间显示一行「加载中…」（小字、次要色），完成后替换为清单 |
| 失败态 | 显示「无法获取工具清单：<原因>」（次要色），并提供「重试」小链接（再次展开即重试） |
| 空清单 | 显示「该服务器未提供工具」 |
| 已禁用/未连接 | **仍可展开**（列举不依赖 opencode 开关），失败按失败态展示 |
| 过滤框 | 沿用现有过滤：仅匹配卡片标题（工具名不参与过滤，避免过滤后展开区与卡片状态不一致） |

## 6. 失败与降级矩阵

| 场景 | 判定 | 展示 |
|------|------|------|
| local 命令不存在 / 非可执行 | `IOException` on start | 无法获取工具清单：启动失败（附 stderr 尾部） |
| 握手或 `tools/list` 超时 | 超过 8s | 无法获取工具清单：超时 |
| server 返回 JSON-RPC error | `error.message` | 无法获取工具清单：<server 返回的错误信息> |
| remote 401/403 | HTTP 状态码 | 无法获取工具清单：需授权（OAuth） |
| remote 网络不可达 | `IOException` | 无法获取工具清单：网络不可达 |
| remote 仅支持遗留 SSE | 405/404 且 URL 含 `/sse` | 无法获取工具清单：暂不支持该传输方式 |
| 分页超过 10 页 | 计数超限 | 展示已得工具，附注「分页超限，仅显示前 N 个」 |
| 进程残留风险 | 任何路径 | `finally` 中 `destroyForcibly()`；进程树用 `ProcessHandle.descendants()` 一并回收 |

## 7. 实施阶段（可独立验证）

| 阶段 | 内容 | 验证 |
|------|------|------|
| S1 | 配置读取兼容（§3.4）：`mcp.servers` + legacy 扁平 + `cwd` | 单测：两种形态解析出同一份 `McpServerDto`（含 `command`） |
| S2 | `McpToolsClient` 协议层：stdio 三步握手 + 分页 + 超时 + 回收 | 单测：用假进程（`sh -c` 回放固定报文）验证解析与超时；rmote 分支用 MockWebServer 式假 HTTP（或注入的 fake transport）验证 JSON/SSE 两种响应 |
| S3 | RPC 打通：`listMcpTools` + 缓存/单飞 | 单测：缓存命中不再发起传输；失败写入 `error` |
| S4 | 前端：卡片展开 + 工具行渲染 + 徽章计数 | 手工冒烟（Swing 无法无头验证）：见 §8.2 |

## 8. 验证方式

### 8.1 自动化

| 用例 | 覆盖 |
|------|------|
| `SettingsMappingUnitTest`（扩展） | `mcp.servers.<name>` 与 `mcp.<name>` 两种形态、`cwd`、`servers`/`timeout` 键名不被误当服务器 |
| `McpToolsClientUnitTest` | initialize/tools/list 报文解析、乱行容错、`nextCursor` 分页、超时回收、stderr 尾部入错误文案 |
| `McpToolsCacheUnitTest` | TTL、配置签名失效、单飞合并 |

### 8.2 手工冒烟（需真机）

1. 设置页 MCP 面板：`codegraph` 卡片展开 → 1 个工具 `codegraph_explore`；徽章变 `Connected (1 tool)`
2. `simple-mem` 展开 → 6 个工具且描述正确；徽章 `Connected (6 tools)`
3. `mintlify-index`(remote) 展开 → 能列出工具；若需 OAuth 则显示「需授权」
4. 把 `command` 改成不存在的可执行文件 → 展开显示「启动失败」，且 `ps` 无残留进程
5. 收起再展开 → 命中缓存（不产生新进程）；点「刷新」后再展开 → 重新拉取
6. 长描述工具行：单行截断 + tooltip 全文；工具数 >10 时展开区内部滚动

## 9. 边界与不做项

| 项 | 本期处理 |
|----|---------|
| 工具 `inputSchema`（参数详情） | 不展示（仅取名与描述）；后续若做「工具详情弹窗」再取 |
| 遗留 SSE 双端点传输（2024-11） | 不支持，遇到即按 §6 报原因 |
| OAuth 授权流程 | 不实现（remote 需授权时提示去 opencode/配置文件侧完成） |
| 内置工具（非 MCP） | 不在本期范围（需求只要求 MCP 下的工具） |
| opencode 侧「工具白名单」与权限 | 不涉及；清单仅用于展示 |
| 与 `GET /api/mcp` 状态的一致性 | 二者解耦：徽章状态取 opencode，工具清单取插件自身连接；可能出现「opencode 显示 failed，但插件能列出工具」——属预期，不做互相纠正 |

## 10. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| local server 被起第二次 | 个别有独占锁/端口占用的 server 可能启动失败或与 opencode 侧互相干扰 | 用完即杀、超时兜底；失败只影响该卡片并显示原因 |
| remote 需鉴权 | 清单拿不到 | 明确提示原因，引导去配置侧处理 |
| 起进程的安全面 | 执行配置里的任意命令 | 命令来源=用户自己的配置文件（与 opencode 同等信任级）；不做额外执行、不传用户输入 |
| 设置页卡顿 | 展开时若同步等待会卡 EDT | 全程 `runAsync` + 回 EDT 渲染（沿用现有骨架）；单 server 8s 预算 |
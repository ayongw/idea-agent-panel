# TSD-06 事件流接入设计

> 插件：OpenCode AI Assistant Panel（`com.ayongw.idea.opencode-idea-panel`）
> 目标：接入 opencode v2 的事件流（`GET /api/event`），把流式文本/推理增量、权限请求、工具调用推到面板 UI，落地「流式对话」与「权限确认」。
> 关联文档：整体架构与端点表见《技术方案》（`docs/tech/技术方案.md`）；设置读写见《TSD-05 设置管理设计》。
> 状态：**设计待评审** —— 协议契约已用真实服务（opencode v2.0.18 本机实例）抓帧实测，见 §4；解析器可据此定稿，其余仍待评审。

## 修订历史

| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-09-29 | 初始版本：现状盘点、选型决议（okhttp + okhttp-sse）、客户端设计、增量语义、阶段计划与测试策略 | agent |
| v1.1 | 2026-09-29 | 回填真实抓帧实测契约（§4）；修正 v1.0 两处错误假设：SSE 帧无 `event:` 行、delta 事件不带 `durable.seq`；增量改为「delta 累积 + ended 全文校准」；补真实连接集成验证（§9） | agent |
| v1.2 | 2026-09-29 | 补齐工具调用链路（`session.tool.*` / `shell.*`）与权限请求（`permission.asked`）实测契约；新增工具流 fixture；确认权限回复枚举与现有 `PermissionDecision` 一致 | agent |
| v1.3 | 2026-09-29 | S5 落地：按 §5.8 实现 shared DTO/格式化 → backend 解析聚合 → RPC 透传 → 前端指示器；收紧 `formatTokens` 规则（不足 1000 保持原值）；§6/§7 回填实施状态 | agent |
| v1.4 | 2026-09-29 | S3a 落地：新增 `SessionStreamState`（累积/校准/75 ms 节流/失败可见）与事件客户端生命周期；新增 `getSessionRunningFlow`；§5.5/§5.9 按实际实现收敛（放弃 delta DTO 与 `StreamingRenderController` 分发方案）；§1/§3/§6/§7/§9 同步 | agent |
| v1.5 | 2026-09-29 | S3b 落地：`ChatList` 按消息 id 就地重渲染气泡、`MessageBubble.renderedContent`、`sessionRunningFlow` 接线（发送/停止切换 + 结束后刷新用量）、「停止」真实中断服务端执行；§6/§7/§9 回填 | agent |
| v1.6 | 2026-09-29 | 补 `OpenCodeEventRealServerITest`（真实连接）；根 `build.gradle.kts` 的 `test` 默认排除 `*ITest`、`-Pit=true` 纳入；回填 §9.1 实跑结果（通过） | agent |
| v1.7 | 2026-09-29 | S4a 落地：对账兜底（执行终态 + 重连成功触发 REST 覆盖）；实测中断收尾契约（`step.failed(aborted)` + `session.execution.interrupted`）并据此新增 `ExecutionInterrupted`、把 `aborted` 视为用户中断（不弹失败气泡）；关闭 §10 遗留 1 | agent |
| v1.8 | 2026-09-29 | S4b 落地：`PendingPermissionDto` + `getPendingPermissionFlow` + 输入区权限确认条（三态回复闭环）；`replyPermission` 改为 `PermissionResponse`；ITest 补权限用例（本机未触发则跳过）；§6/§7/§9 回填 | agent |
| v1.9 | 2026-09-29 | S4c：核对打包产物（`0.1.0.28.zip`：okhttp/okhttp-sse/okio 已打包、无 gson、`since-build=261`、新增类齐备）；README 依赖表与权限确认描述同步；S1–S5 全部阶段收口 | agent |
| v1.10 | 2026-09-29 | S6 落地：工具调用/结果卡片化 —— 补抓 REST 工具部件实测契约（§4.7）；shared 新增 `ToolCallDto`；后端解析 `content[]` 的 `text`/`tool` 部件并一消息多气泡；`SessionStreamState` 消费 `session.tool.*` 就地更新卡片、执行终态收尾；前端工具卡片渲染与统一就地刷新；§2/§5.5/§5.7/§5.9/§6/§7/§9/§10 回填 | agent |
| v1.11 | 2026-09-30 | S6b：实测 `GET /api/session/{id}/message` 的 `data[]` 为**最新在前**，对账/加载统一反转为「最早在前」（修复收到回复后列表看似被清空）；新增运行日志（事件流状态、REST 失败、会话加载与对账结果、流式入列），见 §5.10；消息区渲染稳定性修复见《TSD-07-主界面布局设计》§3.4 | agent |
| v1.12 | 2026-09-30 | S6c：本地回声气泡改用 `/prompt` 返回的 user 消息 id（§5.7 实测契约：与消息列表同一 id），消除对账换 key 导致的闪烁；发送期间流式气泡先到时按「发送前条数」插入用户消息，保证问答顺序 | agent |
| v1.13 | 2026-09-30 | 传输层决策变更：REST 与事件流**统一到同一 OkHttp 客户端**（§2 结论 3、§3 现状表更新为「三种传输并存 → 统一」）；迁移任务与验收见《TSD-30》Phase 2.7。最低支持平台提升至 2026.2（`sinceBuild=262`），官方 `DebouncedUpdates` 转为可用 | agent |

---

## 1. 背景与目标

### 1.1 背景

- 设计初期的缺口（已随 S2/S3a 收敛）：事件客户端与事件→后端会话状态的接线已落地，`ChatMessage` 内容随事件流式累积、运行态由事件驱动；前端气泡刷新（S3b）与对账/权限卡片（S4）待实施。
- UI 侧渲染骨架已就绪：消息气泡支持「就地对内容做重渲染」（`MessageBubble.updateStreamingText` / `updateReasoningContent`）；`StreamingRenderController` 的 delta 缓冲方案未采用，仅保留 `cancelStreaming` 用于清空。
- 前后端推送通路已存在：前端订阅的是 `messagesFlow`（`ChatMessage` 列表）与 `allSessionsFlow`；本轮新增 `sessionRunningFlow`。**不需要新增推送机制**。
- opencode v2 仅 `/api/*` 是真实接口（v1 路径回落 SPA HTML 且 HTTP 200），事件流端点为 `GET /api/event`。

### 1.2 目标

1. 接入 `/api/event`，将事件映射为面板状态：文本/推理增量、权限请求、工具调用与结果、错误、空闲态。
2. 断线自愈：指数退避重连 + 状态对账，不丢终态、不重复渲染。
3. REST 传输：立项期决定「不改变现有形态（仍走 JDK 自带）」，仅为事件流引入 okhttp + okhttp-sse；**2026-09-30 决策变更为「REST 与事件流统一到同一个 OkHttp 客户端」**（一套认证 / 超时 / 代理 / 日志配置，且 `mockwebserver` 测试栈已在仓库），迁移任务与验收见《TSD-30-会话面板整体优化方案》Phase 2.7。

## 2. 结论先行

| 结论 | 说明 |
|------|------|
| 事件流选型 | `com.squareup.okhttp3:okhttp:4.12.0` + `okhttp-sse:4.12.0` |
| 选型依据 | 平台不提供 SSE 客户端：`Contents/lib` 下无任何 sse jar；`intellij.libraries.http.client.jar` 是 Apache HttpClient；`HttpRequests` 只有 `request/head/delete/post/put/patch` 与 `Request.getInputStream()/getReader()/readString()/readBytes()`，无 SSE 帧解析 |
| 依赖归属 | `opencode-backend` 的 `implementation`（需随插件分发；okio 由 okhttp 传递带入）。gson 维持 `compileOnly`（IDE 提供），二者不冲突 |
| 推送通路 | 复用既有 RPC `Flow`：后端把事件写进 `MutableStateFlow`，前端订阅即可，新增推送机制为 0 |
| 帧解析方式 | **实测：SSE 帧只有 `data:` 行，没有 `event:` 行**；事件类型在 `data` JSON 的 `type` 字段内，必须解析 JSON 后按 `type` 分发（不能用 `EventSourceListener.onEvent(type, ...)` 的参数分发） |
| 增量语义 | 消费 `session.text.delta` / `session.reasoning.delta` 累积；**实测：delta 事件不带 `durable`**，故以 `session.(text\|reasoning).ended` 的全文做终态校准（该事件带 `durable.seq`） |
| 流式承载 | **不引入 delta DTO**：面板消息模型是 `ChatMessage`（非 parts），故由后端按消息 id 合并「累计全文」（75 ms 节流）经既有 `messagesFlow` 推送，前端就地刷新已有气泡 |
| 心跳 | 实测存在注释帧 `: heartbeat`（空闲时出现），可作为应用层存活检测依据，无需自造 watchdog 心跳 |
| 工具与权限事件 | 实测已获取：`session.tool.input.started/ended` → `session.tool.called` → `shell.created` → `session.tool.progress` → `shell.exited` → `session.tool.success`；权限请求 `permission.asked`（`data.id` 为 `per_` 前缀）；回复枚举 `once/always/reject` 与现有 `PermissionDecision` 完全一致 |
| 工具卡片数据源 | **REST 权威 + SSE 补运行中态**：工具部件随 `GET /api/session/{id}/message` 回放（实测 `content[]` 含 `tool` 部件，四态见 §4.7），事件流只负责流式期间的即时态；卡片 id 统一用 `call_*`，两路可原地互相覆盖 |
| 对账策略 | 沿用「SSE 不可单独信任」：重连成功、`durable.seq` 跳变、执行失败时，触发一次 `GET /api/session/{id}/message` 幂等覆盖 |
| 测试基准模型 | 真实连接验证固定使用 OpenCode Zen 免费模型 `opencode/mimo-v2.6-flash-free`（默认模型 github-copilot 未授权会直接失败） |

## 3. 现状盘点（实测）

| 项 | 现状 | 证据 |
|---|---|---|
| REST 客户端 | 现状：JDK 自带 `HttpURLConnection`，PATCH 单独走 `java.net.http.HttpClient`（与事件流的 okhttp 并存 = **三种传输方式**）；目标：统一到 okhttp 客户端（见 §2 结论 3 与《TSD-30》Phase 2.7） | `opencode-backend/.../repository/OpenCodeRestClient.kt` |
| 鉴权 | HTTP Basic，用户名默认 `opencode`，密码为空则不鉴权；`authHeaderValue()` 已封装 | 同上 |
| 事件消费 | `OpenCodeEventClient` + `SessionStreamState`：事件驱动的流式内容与运行态（S3a 已实施） | `opencode-backend/.../event/` |
| 状态流 | `messagesFlow`（`ChatMessage` 列表）承接流式内容；`sessionRunningFlow` 承接运行态；`getSessionStateFlow` 仍为旧映射，未被前端订阅 | `BackendChatRepositoryModel` / `BackendChatRepositoryRpcApi` |
| 部件模型 | `MessagePart.PartType` 已含 `TEXT/CODE/REASONING/PERMISSION/TOOL_USE/TOOL_RESULT/ERROR`，`isStreaming` 为 `@Transient` | `opencode-shared/.../MessagePart.kt` |
| UI 渲染 | 气泡支持按消息内容就地重渲染（`updateStreamingText` / `updateReasoningContent`）；`ChatList` 尚未对「已存在 id 的消息」应用刷新（S3b 待接线） | `opencode-frontend/.../ui/MessageItem.kt`、`ChatList.kt` |
| 测试栈 | JUnit 4，单测文件以 `UnitTest` 结尾，当前 99 例全绿 | `src/test/kotlin/...` |

## 4. 协议契约（已用真实服务实测）

> 实测环境：opencode v2.0.18，本机 `opencode serve --port 4097`（`OPENCODE_SERVER_PASSWORD` 指定密码），模型 `opencode/mimo-v2.6-flash-free`。抓帧全文见 §4.4。

### 4.1 帧格式

- 每个事件是一个 `data: <单行 JSON>`，**没有 `event:` 行**（实测 29 帧中 `event:` 行数 = 0）。
- 空闲时服务端发送注释帧 `: heartbeat`（实测 5 帧），客户端必须忽略或以 `:` 前缀处理。
- 多行 `data:` 未出现（单行 JSON），但解析器仍应按 SSE 规范拼接多行 `data:` 以保持健壮。
- 响应头：`content-type: text/event-stream`、`cache-control: no-cache, no-transform`、`x-accel-buffering: no`。
- **fixture 必须保留帧结束空行**：SSE 事件以空行结束。首版 fixture 逐帧拼接时丢了空行，okhttp-sse 便永不下发事件（单测以「等待超时」暴露）。`src/test/resources/sse/*.txt` 现按 `帧\n\n` 规范生成。

### 4.2 事件信封

```json
{"id":"evt_0ed2315a3001p2zVJqi7dQgjfl","created":1790684894627,
 "type":"session.text.delta","location":{"directory":"/tmp/opencode-itest-project"},
 "data":{"sessionID":"ses_...","assistantMessageID":"msg_...","ordinal":0,"delta":"PONG"}}
```

| 字段 | 说明 |
|---|---|
| `id` | 事件 ID（`evt_` 前缀），可作 SSE `Last-Event-ID` |
| `created` | 毫秒时间戳 |
| `type` | 事件类型（点分命名），**分发依据** |
| `location.directory` | 所属项目目录 |
| `data.sessionID` | 会话 ID（多会话路由键） |
| `durable` | 仅**里程碑事件**携带：`{aggregateID, seq, version}`，`aggregateID` = 会话 ID，`seq` 在该会话内单调整递增 |

> 关键实测结论：**`session.text.delta`、`session.reasoning.delta`、`session.usage.updated` 不带 `durable`**；`session.*.started/ended`、`step.*`、`execution.*`、`inbox.*`、`model.selected` 等带 `durable.seq`（实测序列 9→12→14→17→22→23→24→25→27→28）。

### 4.3 事件族与 payload（实测，仅列本插件需要关心的）

| type | data 关键字段 | durable |
|---|---|---|
| `server.connected` | `{}` | 无 |
| `session.inbox.enqueued` | `inboxID`、`sessionID`、`item{type, payload{text}, delivery}` | 有 |
| `session.inbox.delivered` | `sessionID`、`inboxID` | 有 |
| `session.execution.started` | `sessionID` | 有 |
| `session.step.started` | `sessionID`、`assistantMessageID` | 有 |
| `session.step.streamed` | `sessionID`、`assistantMessageID`（**不含文本**，仅表示有流式活动） | 有 |
| `session.model.selected` | `sessionID`、`model{id, providerID}` | 有 |
| `session.reasoning.started` | `sessionID`、`assistantMessageID`、`ordinal`、`state{reasoningField}` | 有 |
| `session.reasoning.delta` | 同上 + `delta` | **无** |
| `session.reasoning.ended` | 同上 + `text`（全文）、`state{reasoningDetails[{type,text,format,index}]}` | 有 |
| `session.text.started` | `sessionID`、`assistantMessageID`、`ordinal` | 有 |
| `session.text.delta` | 同上 + `delta` | **无** |
| `session.text.ended` | 同上 + `text`（全文） | 有 |
| `session.step.ended` | `sessionID`、`assistantMessageID`、`finish`、`rawFinish`、`cost`、`tokens{input,output,reasoning,cache{read,write}}`、`snapshot`、`files[]` | 有 |
| `session.usage.updated` | `sessionID`、`cost`、`tokens{...}` | **无** |
| `session.execution.succeeded` | `sessionID` | 有 |
| `session.execution.interrupted` | `sessionID` | 有（实测：用户中断的**终态事件**，不会再有 `execution.failed/succeeded`） |
| `session.step.failed` | `sessionID`、`assistantMessageID`、`error{type,message,status}`、`snapshot`、`files[]` | 有（用户中断时 `error.type=aborted`、`message=Step interrupted`） |
| `session.execution.failed` | `sessionID`、`error{type,message,status}` | 有 |
| `session.tool.input.started` | `sessionID`、`assistantMessageID`、`id`（工具调用 ID，`call_` 前缀）、`name`（工具名，如 `shell`） | 有 |
| `session.tool.input.ended` | 同上 + `text`（入参原始 JSON 字符串，如 `{"command": "echo hello"}`） | 有 |
| `session.tool.called` | 同上 + `input`（入参对象）、`executed` | 有 |
| `session.tool.progress` | 同上 + `metadata{shellID}` | 无 |
| `session.tool.success` | 同上 + `content[{type,text}]`（**工具输出正文**）、`metadata{status,truncated,exit}`、`executed` | 有 |
| `shell.created` | `info{id,status,command,cwd,shell,file,metadata{sessionID},time{started}}` —— **无顶层 `sessionID`**，需从 `info.metadata.sessionID` 取 | 无 |
| `shell.exited` | `id`、`exit`、`status` | 无 |
| `permission.asked` | `id`（`per_` 前缀，即回复用的 requestID）、`sessionID`、`action`（如 `external_directory`）、`resources[]`、`save[]`、`source{type,messageID,id}` | 无 |
| 心跳注释帧 | `: heartbeat`（无 data） | — |

其它全局广播实测出现但本插件暂不使用：`session.created`、`project.updated`、`provider.updated`、`model.updated`、`agent.updated`、`skill.updated`、`command.updated`、`integration.updated`、`vcs.branch.updated`、`websearch.updated`、`reference.updated`、`plugin.updated`、`mcp.status.changed`、`mcp.resources.changed`、`session.instructions.updated`（后两者中 `mcp.*` 在设置页 MCP 状态刷新时有用）。

### 4.4 fixture（已入库）

| 文件 | 内容 |
|---|---|
| `src/test/resources/sse/real-session-success.txt` | 成功流（`PONG` 轮次）：`server.connected` → `inbox.*` → `execution/step.*` → `reasoning/text.*` → `usage/execution.succeeded`，含心跳注释帧 |
| `src/test/resources/sse/real-session-error.txt` | 失败流（copilot 模型未授权）：`step.failed` + `execution.failed`（`provider.invalid-request` HTTP 400） |
| `src/test/resources/sse/real-session-tool-call.txt` | 工具链路：`tool.input.*` → `tool.called` → `shell.created/progress/exited` → `tool.success`，并含 `permission.asked` |

fixture 按**轮次时间窗**切分，保证同一 fixture 内 `text.delta` 与 `text.ended` 属于同一条 assistant 消息（断言因此可按 `(assistantMessageID, ordinal)` 分组）。

抓帧时已将本机绝对路径脱敏为 `/tmp/opencode-itest-project`。

### 4.5 复现步骤（真实连接）

```bash
export OPENCODE_SERVER_PASSWORD=itest-oc-panel
opencode serve --port 4097 &
BASE=http://127.0.0.1:4097; AUTH="opencode:$OPENCODE_SERVER_PASSWORD"

curl -sN -u "$AUTH" "$BASE/api/event" > raw.txt &        # 挂事件流

SID=$(curl -s -u "$AUTH" -X POST -H 'content-type: application/json' \
        -d '{"title":"sse capture"}' "$BASE/api/session" | jq -r .data.id)

# 必须切到 OpenCode Zen 免费模型，否则默认 copilot 模型未授权 → step.failed
curl -s -u "$AUTH" -X POST -H 'content-type: application/json' \
     -d '{"model":{"id":"mimo-v2.6-flash-free","providerID":"opencode"}}' \
     "$BASE/api/session/$SID/model"

curl -s -u "$AUTH" -X POST -H 'content-type: application/json' \
     -d '{"text":"Reply with exactly: PONG"}' "$BASE/api/session/$SID/prompt"
```

### 4.6 鉴权实测

- `/openapi.json` 中 `/api/event` 声明的 `security: []`，但**实测不带凭据仍返回 401**（`{"_tag":"UnauthorizedError","message":"Authentication required"}`）→ 事件流必须带 HTTP Basic。
- 用户名 `opencode`、密码取 `OPENCODE_SERVER_PASSWORD`（自启实例）或 `~/.config/opencode/service.json`（外部实例）。
- 观察项：本机 4096 端口由 OpenCode 桌面端启动的实例，其凭据**不在** `service.json`，用该文件的密码访问一律 401；说明"外部实例凭据发现"存在盲区，需在设置页显式配置（与 TSD-05 的凭据来源一致，本条不改变现有行为）。

### 4.7 REST 工具部件契约（实测）

工具卡片**以 REST 为权威**：对账与切会话回放历史都依赖它，SSE 只补流式期间的即时态（§5.5）。

实测 `GET /api/session/{id}/message` 的助手消息 `content[]` 按顺序含 `reasoning` / `text` / `tool` 三类部件：

```json
{"type":"tool","id":"call_a446f002537b4a22adf1b620","name":"shell","executed":false,
 "state":{"status":"completed","input":{"command":"echo toolrest"},
          "content":[{"type":"text","text":"toolrest\n"}],
          "metadata":{"status":"completed","truncated":false,"exit":0}},
 "time":{"created":1790695036792,"ran":1790695037210,"completed":1790695037244}}
```

`state.status` 四态（取自 `openapi.json` 的 `Session.Message.ToolState.*`）：

| status | `input` | 输出所在字段 | 面板映射枚举 |
|---|---|---|---|
| `streaming` | 字符串（未解析完的入参片段） | 无 | `STREAMING` |
| `running` | 对象 | 无 | `RUNNING` |
| `completed` | 对象 | `content[].text`；`metadata.exit` / `metadata.truncated` | `COMPLETED` |
| `error` | 对象 | 无 `content` 时退回 `error.message` | `ERROR` |

面板映射规则：**一条助手消息 → 多个气泡**，按部件顺序产出——正文气泡仍复用消息 id（落在首个 `text` 部件的位置，多个 `text` 部件合并），工具卡片 id 复用部件 `id`（`call_` 前缀），与 SSE 侧 `session.tool.*` 的 `callID` 同值，两路可原地互相覆盖。

> `reasoning` 部件暂不渲染，见 §10 遗留 4。

## 5. 方案设计

### 5.1 总体架构

```
opencode serve ──GET /api/event (SSE: data-only + heartbeat)──► OpenCodeEventClient (backend)
                                                                     │ 解析 data JSON → OpenCodeEvent
                                                                     ▼
                                                     BackendChatRepositoryModel
                                                     MutableStateFlow<SessionStateDto>（delta 累积，ended 校准）
                                                                     │ RPC Flow（既有通道）
                                                                     ▼
                                                     FrontendChatRepositoryModel / ChatViewModel
                                                                     │ delta 分发
                                                                     ▼
                                                     StreamingRenderController（75ms 批量 → EDT）
```

### 5.2 依赖接入

```kotlin
// opencode-backend/build.gradle.kts
dependencies {
    implementation(project(":opencode-shared"))
    compileOnly("com.google.code.gson:gson:2.13.2")   // 不变：由 IDE 提供
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")  // 仅测试
}
```

- 引入后需重新核对包体（预期 +1MB 量级）与 `README` 依赖表状态（由「接入时启用」改为已启用）。
- okhttp 4.12 目标 Java 8、Kotlin 1.8/1.9 metadata，运行在 2026.x 的平台 kotlin-stdlib 上兼容。

### 5.3 OpenCodeEventClient（backend 新增）

| 关注点 | 设计 |
|---|---|
| 生命周期 | 随 `BackendChatRepositoryModel`（Project 级）创建与释放；Server 地址/用户名/密码变更时重建 |
| 连接参数 | 独立 `OkHttpClient`：`connectTimeout=5s`、**`readTimeout=0`**（长连接禁用读超时）、`retryOnConnectionFailure=false`（重连由本类统一控制） |
| 鉴权 | 复用 `OpenCodeRestClient.authHeaderValue()`（抽为共享工具），以 `Authorization` 头注入（实测必需，见 §4.6） |
| 事件源 | `EventSources.createFactory(client).newEventSource("$base/api/event", listener)`；**只实现 `onEvent(id, type, data)` 中的 `data` 解析**，忽略其 `type` 参数（服务端不填，见 §4.1） |
| 心跳处理 | 心跳是注释行，**okhttp-sse 不上抛**（已由单测 `emitsNothingForHeartbeatOnlyStream` 验证）→ 不需要也不应把它当成事件 |
| 重连 | `onClosed`/`onFailure` 统一走指数退避（1s→2s→4s…上限 30s，带抖动）；重连成功后触发一次对账 |
| 静默断连 | 用**读超时**（默认 60 s）替代自造 watchdog：注释帧持续产生 socket 读活动，若 60 s 无任何字节则判定链路已死 → `onFailure` → 重连 + 对账 |
| 停止 | `stop()` 中 `eventSource.cancel()` → `client.dispatcher.executorService.shutdown()` → `client.connectionPool.evictAll()`，避免连接/线程泄漏 |
| 线程纪律 | 回调只做「解析 + 写 StateFlow/缓冲」，绝不触碰 UI；UI 更新由 EDT 与前端侧负责 |

### 5.4 事件模型（对齐实测事件名）

```kotlin
sealed class OpenCodeEvent {
    // 会话/执行生命周期（带 durable.seq）
    data class SessionCreated(val sessionId: String, val directory: String) : OpenCodeEvent()
    data class ExecutionStarted(val sessionId: String) : OpenCodeEvent()
    data class StepStarted(val sessionId: String, val assistantMessageId: String) : OpenCodeEvent()
    data class StepStreamed(val sessionId: String, val assistantMessageId: String) : OpenCodeEvent()
    data class StepEnded(val sessionId: String, val assistantMessageId: String, val finish: String?, val tokens: TokenUsageDto?) : OpenCodeEvent()
    data class StepFailed(val sessionId: String, val assistantMessageId: String?, val error: OpenCodeErrorDto) : OpenCodeEvent()
    data class ExecutionSucceeded(val sessionId: String) : OpenCodeEvent()
    data class ExecutionFailed(val sessionId: String, val error: OpenCodeErrorDto) : OpenCodeEvent()

    // 推理/文本流（delta 无 durable；ended 带全文）
    data class ReasoningStarted(val sessionId: String, val assistantMessageId: String, val ordinal: Int) : OpenCodeEvent()
    data class ReasoningDelta(val sessionId: String, val assistantMessageId: String, val ordinal: Int, val delta: String) : OpenCodeEvent()
    data class ReasoningEnded(val sessionId: String, val assistantMessageId: String, val ordinal: Int, val text: String) : OpenCodeEvent()
    data class TextStarted(val sessionId: String, val assistantMessageId: String, val ordinal: Int) : OpenCodeEvent()
    data class TextDelta(val sessionId: String, val assistantMessageId: String, val ordinal: Int, val delta: String) : OpenCodeEvent()
    data class TextEnded(val sessionId: String, val assistantMessageId: String, val ordinal: Int, val text: String) : OpenCodeEvent()

    // 工具调用（入参 → 执行 → 输出）与权限请求
    data class ToolInputStarted(val sessionId: String, val assistantMessageId: String, val callId: String, val toolName: String) : OpenCodeEvent()
    data class ToolInputEnded(val sessionId: String, val assistantMessageId: String, val callId: String, val rawInput: String) : OpenCodeEvent()
    data class ToolCalled(val sessionId: String, val assistantMessageId: String, val callId: String, val inputJson: String) : OpenCodeEvent()
    data class ToolProgress(val sessionId: String, val assistantMessageId: String, val callId: String, val shellId: String?) : OpenCodeEvent()
    data class ToolSucceeded(val sessionId: String, val assistantMessageId: String, val callId: String, val output: String, val exit: Int?, val truncated: Boolean) : OpenCodeEvent()
    data class PermissionAsked(val sessionId: String, val requestId: String, val action: String, val resources: List<String>, val sourceMessageId: String?) : OpenCodeEvent()

    data class ModelSelected(val sessionId: String, val modelId: String, val providerId: String) : OpenCodeEvent()
    data class UsageUpdated(val sessionId: String, val tokens: TokenUsage?, val cost: Double?) : OpenCodeEvent()
    data class ShellCreated(val sessionId: String?, val shellId: String, val command: String, val cwd: String?, val outputFile: String?) : OpenCodeEvent()
    data class ShellExited(val shellId: String, val exit: Int?, val status: String?) : OpenCodeEvent()
    data object ServerConnected : OpenCodeEvent()
    data class Unexpected(val type: String) : OpenCodeEvent()   // 未知类型：计数并忽略
}
```

> 实现命名与本节略有收敛：用量/错误用 `TokenUsage`、`OpenCodeError`（backend 内部模型，非 DTO）；**不设 `Heartbeat`**（注释帧由 okhttp-sse 吞掉，存活检测走读超时）。字段名与实测 payload 一一对应，代码见 `opencode-backend/.../backend/event/`。
> 「中断/取消」触发后的事件尚未实测 —— 见 §10 遗留 1。

### 5.5 状态与增量语义（按实现收敛）

实现载体：`opencode-backend/.../event/SessionStreamState.kt` —— 纯逻辑状态机，无 IntelliJ / 网络依赖，可直接单测。

- **承载方式**：面板消息模型是 `ChatMessage`（`id` / `content` / `type`），不是 parts。因此**不做「前端 delta 分发」**，而是由后端按消息 id 把流式消息合并进消息列表（新气泡追加、已有气泡就地更新）；`ChatMessage.content` 始终是累计全文，前端只负责「内容变了就刷新这个气泡」。
- **累积规则**：正文 / 推理按 `(assistantMessageID, ordinal)` 累积（同一消息多段按 `ordinal` 升序以 `\n` 拼接）；`session.(text|reasoning).ended` 的全文**覆盖校准**（防丢帧 / 重复）。
- **气泡 id**：正文气泡 id 直接复用 `assistantMessageID`（与 REST 对账的消息 id 一致，`GET /api/session/{id}/message` 覆盖时能原地替换）；推理气泡 id 为 `<assistantMessageID>#reasoning`。
- **气泡类型**：正文 → `ChatMessageType.TEXT`；推理 → `ChatMessageType.AI_THINKING`（复用既有推理样式）。**空内容气泡不产出**，避免出现空气泡（失败流因此只剩失败气泡）。
- **刷新节流**：聚合间隔 75 ms（与前端渲染节奏对齐）。delta 事件仅在距上次发布 ≥75 ms 时发布；内容不丢（每次发布都从缓冲重算全文），`ended` 等里程碑恒发布以补齐尾帧。
- **执行态**：`isRunning` —— `execution.started` / `step.started` 置 true，`execution.succeeded` / `execution.failed` / `step.failed` 置 false；经 `getSessionRunningFlow` 推到前端切换「发送 / 停止」态。
- **失败可见**：`step.failed` / `execution.failed` 把 `error.type: error.message` 落成一条正文气泡（id `opencode-failure:<assistantMessageID|execution>`），避免面板卡在「响应中」。**例外**：用户中断（`error.type == "aborted"`）不算失败，不弹失败气泡。
- **用户中断**（实测契约，见 §4.3）：`reasoning/text.ended`（补全文）→ `step.streamed` → `step.failed(type=aborted)` → **`session.execution.interrupted`**；`ExecutionInterrupted` 与 `aborted` 都只把运行态置 false，正文保留。
- **会话路由**：只处理 `sessionID == 当前会话` 的事件；`shell.exited` 等无 sessionID 的事件本阶段忽略。
- **权限请求**：`permission.asked` → `PendingPermissionDto{sessionId, requestId, action, resources}`（`requestId` 实测为 `per_` 前缀，即 `data.id`）→ RPC `getPendingPermissionFlow` → **输入框上方权限确认条**（三按钮 `once / always / reject`，与后端 `PermissionDecision` 一致）；点击后本地立即收起（不等服务端事件）并回 `POST /api/session/{id}/permission/{requestId}`。执行终态 / 中断 / 切会话 / 重连均清空待决项。
- **工具卡片**：`session.tool.*` 按 `callID` 就地更新卡片（`input.started` → `STREAMING`、`input.ended` 补入参、`called` → `RUNNING`、`success` → `COMPLETED` 带输出/退出码/截断标记）；执行终态（`execution.succeeded` / `interrupted` / 非 `aborted` 的 `step.failed`）把仍在流式/执行中的卡片收尾（`COMPLETED` / `ERROR`），权威结果由随后的 REST 对账覆盖（§4.7）。卡片数据模型见 shared `ToolCallDto`；`shell.*` 不上卡片（shell 信息已由 `session.tool.*` 覆盖），权限走确认条。
- 会话切换 / 新建 / 删除时重置状态机（缓冲、运行态、失败气泡），避免串值。

> `durable.seq` 未落地为 DTO 字段：本阶段以「全文覆盖 + 重连后 REST 对账」（S4）兜底，不用序号做增量去重。

### 5.8 会话用量与上下文占比展示（需求新增）

**目标**：在会话面板**输入框下方**常驻显示当前会话的 token 用量与上下文占用比例。

**数据来源（契约已确认）**

| 数据 | 来源 | 说明 |
|---|---|---|
| 会话累计用量 | `Session.Info.cost` / `Session.Info.tokens`（`GET /api/session`、`/api/session/{id}`） | 权威值，用于初始化与对账 |
| 实时刷新 | `session.usage.updated`（无 `durable`）、`session.step.ended.tokens`（有 `durable`） | SSE 接通后实时；接通前在切换会话/发送消息后各拉一次 REST |
| 单次请求上下文占用 | 最近一条 `session.step.ended.tokens.input` | 占比**分子** |
| 模型上下文窗口 | `Model.Info.limit.context`（必需字段，`GET /api/model`） | 占比**分母**，按当前会话模型 `providerID/modelID` 匹配 |

`TokenUsage.Info` 结构（实测契约）：`{input, output, reasoning, cache:{read, write}}`，会话对象另有 `cost`。

**占比口径（关键）**：`上下文占比 = 最近一次 step 的 input tokens ÷ 该模型 limit.context`。**不可**用 `Session.Info.tokens.input` 当分子 —— 那是会话累计值；多轮会话的累计口径需实测确认（§10 遗留 4）。

**UI 设计**

- 位置：`PromptInput` 底部工具条行（`createToolbarRow()`）的 **EAST 侧**，与「审核类型 / 模式 / 模型」同排、右对齐，不新增行高。
- 文本：`↑12.3k ↓400 · 缓存 8.1k · 上下文 48%`（`formatTokens` 规则：≥100 万 → `1.2M`，≥1000 → `12.3k`，不足 1000 保持原值）；占比 ≥ 80% 时用警示色（`JBColor` 双主题）。
- 悬浮 tooltip：展开 input / output / reasoning / cache read / cache write / cost / 窗口大小。
- 无数据或模型窗口未知时：只显示绝对用量、不显示百分比；完全不显示占位而非显示 0，避免误读。
- 会话切换时立即清空旧会话数值，避免串值。

**变更文件**：见 §6 中标注「S5」的十一行（shared DTO/格式化 → backend 解析与聚合 → RPC 透传 → 前端指示器与接线 → 单测）。

**测试**：格式与占比计算抽为纯函数（`ContextUsageFormatter`），单测覆盖千分位、百分比取整、超窗（>100% 截断显示 `100%+`）、无窗口不显百分比、无数据空显示。

### 5.9 前端消费

- 后端已把流式内容合并进消息列表并经既有 `messagesFlow` 推出；前端需要**在内容变化时刷新已有气泡**——由 `ChatList.setMessages` 对已存在 id 的消息调用 `MessageBubble.syncWith`：`TEXT` → `updateStreamingText`，`AI_THINKING` → `updateReasoningContent`，`TOOL` → 重建工具卡片（运行中 → 完成/失败）。
- 新增 `getSessionRunningFlow` → `FrontendChatRepositoryModel.sessionRunningFlow`：`ChatViewModel` 据此把输入框切到「停止」态，执行结束回到可发送并刷新用量（§5.8）——这也补齐了 §5.8 在事件流接通前「用量只在切换/发送后更新」的缺口。
- 不做全量 diff：按消息 id 比对内容是否变化即可（`MessageBubble.syncWith` 内部按类型取指纹：正文=内容、工具卡片=「状态+入参+输出」），无二次渲染遍历。
- 心跳与未知类型不进状态流，避免无谓的 RPC 流量。

### 5.7 对账与容错

| 场景 | 处理 |
|---|---|
| 重连成功（含首次连接） | 事件客户端 `CONNECTED` 回调触发一次对账：`GET /api/session/{id}/message` 覆盖本地并清空流式缓冲 |
| 执行终态（`execution.succeeded` / `failed` / `interrupted`） | 同上触发一次对账（事件可能丢，REST 不会）；`step.failed` 不触发（多步执行仍在继续） |
| delta 丢帧（无 seq 可校验） | 由 `session.text.ended` 的全文覆盖兜底 |
| 工具卡片（REST 权威） | 对账/切会话按 `call_*` id 原地替换卡片内容，不丢卡片、可回放历史工具调用（§4.7）；SSE 只补流式期间的即时态 |
| `durable.seq` 跳变/缺口 | 不做序号校验（本轮不落地 `durableSeq`），由上述「终态 + 重连对账」覆盖 |
| 心跳超时 | 判定链路已死 → 主动重建连接 → 重连成功即对账 |
| 用户中断（实测） | 本地先置 false 让「停止」即时生效；服务端随后补 `step.failed(aborted)` + `execution.interrupted`，不弹失败气泡，正文由 `reasoning/text.ended` 补全 |
| permission 到达但 UI 未响应 | 由对账兜底补齐（事件可能丢）；回复后本地先收起卡片，UI 不会重复回复 |
| 401/403 | 事件客户端标记 `UNAUTHORIZED` 并停止重连，交由设置页（凭据配置处）处理 |
| **REST 消息顺序（实测）** | `GET /api/session/{id}/message` 的 `data[]` 为**最新在前**（下标 0 最新），而面板要求「最早在前」（与 SSE 追加顺序一致）→ 对账与 `loadMessages` 统一反转后再映射气泡（`toBubbles`） |
| **本地回声气泡 id（实测）** | `POST /api/session/{id}/prompt` 返回 `Session.Inbox.User`，其 `data.id`（必填、`^msg_`）**与 `GET /message` 中该 user 消息的 id 是同一个**（已用 openapi + 抓包核对）→ 发送时就用它作为本地回声气泡 id，对账后无需换 key，消除「删掉重建」的闪烁。`/command` 的 200 响应未声明 schema，解析失败时回退本地 id |

### 5.10 运行日志

面板此前**无任何日志**（`idea.log` 中 `#com.ayongw` 计数为 0），线上问题只能靠猜。本阶段补齐关键节点日志（`com.intellij.openapi.diagnostic.Logger`，只记状态与数量，**不记账号密码**）：

| 位置 | 级别 | 内容 |
|---|---|---|
| `OpenCodeEventClient` | INFO | 启动连接、连接成功（含 HTTP 码）、服务端断开、重连退避时长 |
| `OpenCodeEventClient` | WARN | 认证失败（只记状态码 401/403）、连接失败（码 + 原因） |
| `OpenCodeRestClient` | WARN | 非 2xx 与 IO 异常（method + path + code + 响应体前 200 字） |
| `BackendChatRepositoryModel` | INFO | 事件流启动/重建、事件流状态变更、执行终态触发对账、发送消息（session/长度/命令名）、流式消息入列（新增条数 + 类型）、新建/切换/删除会话、加载会话消息与对账结果（REST 条数 → 气泡条数） |
| `BackendChatRepositoryModel` | WARN | REST 失败（对账/加载/发送/会话操作，含失败原因）、无可用会话回退模拟响应 |
| `ChatList` / `MessageItem`（**临时诊断，定位后移除**） | INFO | `[diag]` 前缀：`setMessages` 消息清单、布局后的容器/视口几何与各气泡 `bounds`、气泡创建信息、代码块高度换算 |

## 6. 变更文件清单

| 文件 | 变更摘要 | 状态 |
|------|---------|------|
| `src/test/resources/sse/*.txt`（success / error / tool-call 三个） | 真实抓帧 fixture（按 SSE 规范留帧结束空行、按轮次切分、路径已脱敏） | **已创建（2026-09-29 实测）** |
| `opencode-backend/build.gradle.kts` | 新增 okhttp、okhttp-sse（`implementation`）与 mockwebserver（`testImplementation`） | **已实施**（`*ITest` 排除落在根 `build.gradle.kts` 的 `test` 任务） |
| `opencode-backend/.../event/OpenCodeEvent.kt` | 新增：事件模型（sealed class）+ `TokenUsage` / `OpenCodeError` | **已实施** |
| `opencode-backend/.../event/OpenCodeEventParser.kt` | 新增：`data` JSON → 事件的纯函数解析（未知类型/缺 sessionID → `Unexpected`） | **已实施** |
| `opencode-backend/.../event/OpenCodeEventClient.kt` | 新增：SSE 客户端（连接/重连退避/读超时存活/401 停止/stop 释放） | **已实施** |
| `opencode-backend/.../repository/OpenCodeAuth.kt` | 新增：Basic 认证头构造（`OpenCodeRestClient` 已改为复用它，单一真源） | **已实施** |
| `src/test/.../OpenCodeEventParserUnitTest.kt` | 新增：回放三个真实 fixture 的解析契约用例 | **已实施** |
| `src/test/.../OpenCodeEventClientUnitTest.kt` | 新增：MockWebServer 回放（连接+鉴权、心跳不产事件、断线重连、401 不重连、stop 释放） | **已实施** |
| `opencode-backend/.../event/SessionStreamState.kt` | 新增：事件驱动流式状态机（按 `(messageID, ordinal)` 累积、`ended` 全文校准、75 ms 节流发布、失败可见） | **已实施（S3a）** |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：事件客户端生命周期（init / 配置变更重建 / Project 销毁释放）、事件→消息合并（按 id 就地更新）、`getSessionRunningFlow`、会话切换重置；**对账兜底**（执行终态 + 重连成功触发 REST 覆盖）；中断不弹失败气泡 | **已实施（S3a + S4a）** |
| `opencode-backend/.../event/OpenCodeEvent.kt`、`OpenCodeEventParser.kt`、`SessionStreamState.kt` | 修改：新增 `ExecutionInterrupted`（`session.execution.interrupted`）；`aborted` 视为用户中断（不弹失败气泡） | **已实施（S4a）** |
| `opencode-shared/.../ChatRepositoryRpcApi.kt` | 修改：新增 `getSessionRunningFlow(projectId, sessionId)`（非当前会话恒 false） | **已实施（S3a）** |
| `opencode-backend/.../BackendChatRepositoryRpcApi.kt` | 修改：`getSessionRunningFlow` 透传 | **已实施（S3a）** |
| `src/test/.../SessionStreamStateUnitTest.kt` | 新增：回放三个真实 fixture，覆盖累积/校准/多段拼接/节流/运行态/失败可见/中断（aborted 不弹失败气泡）/重置（13 例） | **已实施（S3a + S4a）** |
| `opencode-shared/.../SessionState.kt` | 原计划为 `SessionStateDto` 增加 `delta`、`durableSeq` | **不再需要**（改为后端合并累计全文，见 §5.5） |
| `opencode-frontend/.../chatApp/ui/ChatList.kt` | 修改：`syncExistingMessages` 对已存在 id 的消息按内容变化就地重渲染（TEXT → `updateStreamingText`，AI_THINKING → `updateReasoningContent`）；删除原「疑似流式」启发式 | **已实施（S3b）** |
| `opencode-frontend/.../chatApp/ui/MessageItem.kt` | 修改：暴露 `renderedContent`（供上游比对是否需要重渲染；思考消息初始为动画故为空串） | **已实施（S3b）** |
| `opencode-frontend/.../viewmodel/*`（`ChatRepositoryApi` / `FrontendChatRepositoryModel` / `ChatViewModel`） | 修改：`sessionRunningFlow` 接线（切换/新建/删除会话时重启订阅）；运行中切「停止」、结束后回可发送并刷新用量；`onAbortSendingMessage` 真实调用 `abortExecution` | **已实施（S3b）** |
| `opencode-event 前端 delta 分发`（原 `StreamingRenderController` 方案） | 该方案未落地：`StreamingRenderController` 仅保留 `cancelStreaming` 用于清空 | **已收敛** |
| `src/test/.../OpenCodeEventParserUnitTest.kt` | 新增：解析纯函数用例（含心跳、未知类型、缺字段、非 JSON） | **已实施** |
| `src/test/.../OpenCodeEventClientUnitTest.kt` | 新增：MockWebServer 回放 fixture（正常流、重复、断线重连、401、半途关闭） | **已实施** |
| `src/test/.../OpenCodeEventRealServerITest.kt` | 新增：真实服务集成验证（`*ITest`，`-Pit=true` 才跑）——① 流式逐次上屏 + 终态校准；② 中断收尾（`outcome=interrupted`、不弹失败气泡） | **已实施**（2 例实跑通过） |
| `build.gradle.kts`（根） | 修改：`test` 默认 `exclude("**/*ITest.class")`，`-Pit=true` 时纳入并把开关透给测试 JVM | **已实施** |
| `opencode-shared/.../SessionUsage.kt` | 新增：`TokenUsageDto` / `SessionUsageDto` + `ContextUsageFormatter`（紧凑格式、千分位、占比与超窗截断，纯函数） | **已实施（S5）** |
| `opencode-shared/.../AgentModelDto.kt` | 修改：`ModelDto` 增加 `contextWindow`（`Model.Info.limit.context`） | **已实施（S5）** |
| `opencode-shared/.../ChatRepositoryRpcApi.kt` | 修改：新增 `getSessionUsage(projectId, sessionId)` | **已实施（S5）** |
| `opencode-backend/.../repository/OpenCodeRestClient.kt` | 修改：解析 `Session.Info.cost/tokens/model`、助手消息 `tokens.input`、`Model.Info.limit.context` | **已实施（S5）** |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：`getSessionUsage`（累计用量 + 最近一次 step 的 input + 按会话模型匹配上下文窗口） | **已实施（S5）** |
| `opencode-backend/.../BackendChatRepositoryRpcApi.kt` | 修改：`getSessionUsage` 透传（异常降级为空快照）；`listModels` 带上上下文窗口 | **已实施（S5）** |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：`_pendingPermission` 待决态（`permission.asked` 挂起；回复/终态/中断/切会话/重连清空）+ `getPendingPermissionFlow` | **已实施（S4b）** |
| `opencode-shared/.../dtos.kt`、`ChatRepositoryRpcApi.kt` | 新增：`PendingPermissionDto`；RPC `getPendingPermissionFlow(projectId, sessionId)` | **已实施（S4b）** |
| `opencode-backend/.../BackendChatRepositoryRpcApi.kt` | 修改：`getPendingPermissionFlow` 透传（非当前会话恒 null） | **已实施（S4b）** |
| `opencode-frontend/.../chatApp/ui/PermissionPrompt.kt` | 新增：权限确认条（动作 + 资源 + 三按钮；无待决项隐藏；点击即收起） | **已实施（S4b）** |
| `opencode-frontend/.../chatApp/ui/PromptInput.kt`、`OpenCodeChatApp.kt` | 修改：输入区上方挂权限确认条；订阅 `pendingPermissionFlow` → EDT 更新；三按钮回调 → `replyPermission` | **已实施（S4b）** |
| `opencode-frontend/.../viewmodel/*` | 修改：`pendingPermissionFlow` 订阅（与执行态同一「随会话变化」的订阅）；`replyPermission` 改为三态 `PermissionResponse` | **已实施（S4b）** |
| `src/test/.../OpenCodeEventRealServerITest.kt` | 新增第 3 例：权限链路（`permission.asked` → 回 `once` → 执行收尾）；本机未触发时按 `Assume` 跳过 | **已实施（S4b）** |
| `opencode-frontend/.../chatApp/ui/ContextUsageIndicator.kt` | 新增：输入框下方右侧用量指示器（≥80% 警示色、无数据整块隐藏、tooltip 明细） | **已实施（S5）** |
| `opencode-frontend/.../chatApp/ui/PromptInput.kt` | 修改：工具条行 EAST 挂指示器，暴露 `updateUsage` | **已实施（S5）** |
| `opencode-frontend/.../viewmodel/ChatRepositoryApi.kt`、`FrontendChatRepositoryModel.kt`、`ChatViewModel.kt` | 修改：`getSessionUsage` 透传；`usageFlow` + 切换/新建会话时清空并刷新、发送/中止/切模型后刷新 | **已实施（S5）** |
| `opencode-frontend/.../chatApp/OpenCodeChatApp.kt` | 修改：订阅 `usageFlow` → EDT 更新指示器 | **已实施（S5）** |
| `src/test/.../ContextUsageFormatterUnitTest.kt` | 新增：紧凑格式、千分位、占比取整、超窗 `100%+`、无窗口不显占比、空数据隐藏、警示阈值 | **已实施（S5）** |
| `src/test/.../OpenCodeRestClientUnitTest.kt` | 修改：补 `cost/tokens/model`、助手 `tokens.input`、`limit.context` 的解析断言 | **已实施（S5）** |
| `opencode-shared/.../ToolCall.kt` | 新增：`ToolCallDto` + `ToolCallStatus`（工具卡片数据模型，REST 与 SSE 共用，四态对齐 `ToolState`） | **已实施（S6）** |
| `opencode-shared/.../ChatMessage.kt`、`dtos.kt` | 修改：`ChatMessageType` 增 `TOOL`、`ChatMessage`/`ChatMessageDto` 增 `tool` 字段（含双向转换） | **已实施（S6）** |
| `opencode-backend/.../repository/OpenCodeRestClient.kt` | 修改：助手消息 `content[]` 按顺序解析 `text`/`tool` 部件（`OpenCodeMessage.parts`、四态映射、`input` 字符串/对象、`exit`/`truncated`） | **已实施（S6）** |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：一条 REST 消息 → 正文气泡 + 工具卡片（按部件顺序），随对账/切会话一并回放 | **已实施（S6）** |
| `opencode-backend/.../event/SessionStreamState.kt` | 修改：`session.tool.*` 产出并按 `callID` 就地更新 `TOOL` 卡片；执行终态收尾（成功/中断→`COMPLETED`、失败→`ERROR`） | **已实施（S6）** |
| `opencode-frontend/.../chatApp/ui/MessageItem.kt`、`ChatList.kt` | 修改：工具卡片渲染（工具名/状态/退出码/入参摘要/输出复用 `CodeBlockPane`）；`syncWith` 统一三种气泡的就地刷新 | **已实施（S6）** |
| `opencode-frontend/.../chatApp/ui/utils/ChatAppColors.kt` | 修改：新增 `Tool` 状态色（进行中/成功/失败/次要文字） | **已实施（S6）** |
| `src/test/.../OpenCodeRestClientUnitTest.kt`、`SessionStreamStateUnitTest.kt` | 新增用例：工具部件四态解析、卡片生命周期/顺序/终态收尾（分支内 +10 例） | **已实施（S6）** |
| `README.md` | 依赖表：okhttp/okhttp-sse 状态由「接入时启用」改为已启用；补事件流说明 | **已实施**（S2 客户端 + 用量功能，2026-09-29） |

## 7. 实施顺序

| 阶段 | 内容 | 完成判据 | 状态 |
|---|---|---|---|
| **S1 抓帧定契约** | 起真实实例抓取成功流、失败流、工具调用流（含权限请求）的帧，确认事件名/payload/心跳/鉴权；固化 fixture；回填 §4 | §4 待确认项有实测答案，fixture 入库 | **已完成（2026-09-29）** |
| **S2 客户端** | 依赖接入 + `OpenCodeEventParser` + `OpenCodeEventClient`（重连/读超时存活/停止） | MockWebServer 回放 fixture 单测全绿 | **已完成（2026-09-29）**：12 例事件单测通过 |
| **S3 通路打通** | 事件 → 流式状态机 → 消息列表（75 ms 节流）→ RPC Flow → 前端就地刷新气泡；运行态驱动「发送/停止」 | 真实连接集成验证通过：面板逐字输出、思考过程可见、首 token 明显提前 | **已完成（2026-09-29）**：S3a 后端 + S3b 前端；真实连接 ITest 实跑通过（§9.1）；面板侧手工验收见 §9.3（待装机执行） |
| **S4 容错收口** | 对账兜底、权限卡片联调、中断清理、401 处理、包体与 README 同步 | 断开 server 重连自愈；权限允许/拒绝闭环；包体核对完成 | **已完成（2026-09-29）**：S4a 对账 + 中断收口、S4b 权限确认条、S4c 包体核对（`0.1.0.28.zip`：okhttp/okhttp-sse/okio 已打包、无 gson、`since-build=261`、新增类齐备）；面板侧手工验收见 §9.3（待装机执行） |
| **S5 用量与占比** | 输入框下方展示当前会话 token 用量与上下文占比（REST 拉取：会话累计用量 + 最近一次 step 的 input + 模型上下文窗口） | 切换/发送/中止/切模型后指示器更新；无窗口不显占比、无数据整块隐藏；`ContextUsageFormatter` 单测全绿 | **已完成（2026-09-29）** |
| **S6 工具卡片** | 工具调用/结果卡片化：REST 工具部件（权威、可对账/回放）+ SSE `session.tool.*`（运行中态）→ `TOOL` 气泡 → 前端卡片与就地刷新 | 四态解析与卡片生命周期单测全绿；对账/切会话不丢卡片；面板侧手工验收见 §9.3 | **已完成（2026-09-29）**：`./gradlew test` 118 例全绿（新增 10 例）；面板侧手工验收见 §9.3（待装机执行） |

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| ~~帧格式与假设不符~~ | ~~解析器返工~~ | **已消除**：S1 实测完成，契约见 §4 |
| delta 事件无 `durable.seq`，无法用序号去重 | 重连后文本重复 | 以 `session.(text\|reasoning).ended` 全文覆盖校准 + 重连后全量对账；`started` 建缓冲、`ended` 封闭 |
| 长连接静默断开 | 面板卡在「响应中」 | 以 `: heartbeat` 时间戳判定存活，超时主动重建 + 对账 |
| 默认模型未授权导致不出词 | 误判为「SSE 没接好」 | 测试固定用 `opencode/mimo-v2.6-flash-free`；实现里把 `step.failed.error` 直接渲染为错误气泡 |
| 引入 okhttp 增加包体与依赖面 | 包体 +1MB 量级；与平台库冲突面 | 版本锁 4.12.0；仅 backend 依赖；打包后核对 jar 清单 |
| 事件高频触发 Flow emit | RPC 通道压力、UI 卡顿 | 后端按 75 ms 聚合一次 emit（与前端渲染节奏对齐） |
| 多会话并发事件 | 串会话串扰 | 按 `data.sessionID` 路由；非当前会话只更新列表状态 |
| 全局广播事件噪声（mcp/command/plugin 等） | 无谓解析与流量 | 解析器只产出关心的事件类型，其余计入 `Unexpected` 计数不 emit |
| 平台升级导致 okhttp 与 stdlib 不兼容 | 运行期 NoSuchMethod | 以 2026.x 为目标平台；升级平台时回归 SSE 用例 |

## 9. 测试策略

### 9.1 真实连接集成验证（必做，不能只靠 mock）

| 项 | 设计 |
|---|---|
| 用例 | `OpenCodeEventRealServerITest`（`*ITest`，Gradle `test` 默认排除；`-Pit=true` 纳入执行） |
| 前置 | 真实服务可达：`OPENCODE_IT_BASE_URL`（默认 `http://127.0.0.1:4097`）、`OPENCODE_IT_PASSWORD`（默认 `itest-oc-panel`）；未加 `-Pit=true`（测试 JVM 侧为 `-Dopencode.it=true`）时 `Assume.assumeTrue` 跳过 |
| 流程 | 起受控实例 → `BackendChatRepositoryModel` 建会话 → 切模型 `opencode/mimo-v2.6-flash-free` → 发 `prompt("Reply with exactly: PONG")` → 断言：执行态 true→false、消息列表**多次**中间态推送（≥2 次）、正文以 `text.ended` 校准为 `PONG`、思考气泡非空、无失败气泡 |
| 工具/权限链路 | 追加一次 `prompt("Use the bash tool to run exactly: echo hello > /tmp/oc-panel-itest-perm.txt")`，轮询 `permission.asked` 并回 `once`，断言执行收尾；本机未触发权限请求（已配置自动允许等）时按 `Assume` 跳过而非失败 |
| 为什么必要 | mock 只能验证"解析与重连逻辑"；真实链路才能发现鉴权、模型未授权、心跳时序、真实帧字段差异等只有真机才暴露的问题 |
| 手工兜底 | 保留 §4.5 的 curl 复现步骤，作为无 IDE 环境时的对证手段 |

**验证结果（2026-09-29，opencode v2.0.18 本机 4097 受控实例）**：`-Pit=true` 实跑 `tests=3 skipped=1 failures=0`——① 创建会话、切免费模型、真实事件流驱动下正文/思考逐次上屏并以终态校准为 `PONG`、执行态正确回落；② 长回答中途 `interrupt`，服务端 `outcome=interrupted`、本地执行态回落且不出现失败气泡；③ 权限链路**本机未触发**（免费模型未发起需授权的工具调用 / 本机已自动允许），按 `Assume` 跳过——`permission.asked` 的字段映射由 fixture 单测覆盖（§9.2），卡片交互待 §9.3 手工验收。命令：

```bash
OPENCODE_SERVER_PASSWORD=itest-oc-panel opencode serve --port 4097 &
./gradlew test -Pit=true --tests "com.ayongw.idea.opencode.OpenCodeEventRealServerITest"
```

### 9.2 单元测试

| 用例 | 类型 | 覆盖点 |
|------|------|--------|
| `OpenCodeEventParserUnitTest` | 单元（JUnit 4） | 回放两个真实 fixture；心跳帧忽略、未知类型计数、字段缺失、非 JSON `data`、多行 `data` 拼接 |
| `OpenCodeEventClientUnitTest` | 单元（MockWebServer） | 正常流、重复事件、断线重连（校验退避与对账调用）、401、服务端半途关闭、心跳超时触发重建 |
| `SessionStreamStateUnitTest` | 单元（JUnit 4） | 回放三个真实 fixture：按 `(messageID, ordinal)` 累积、`ended` 全文覆盖校准、多段拼接顺序、75 ms 节流发布、运行态生命周期、失败可见（含缺 message 兜底）、无关事件不发布、`reset` 清空 |
| `SessionStreamStateUnitTest`（工具部分） | 单元（JUnit 4） | 回放 `real-session-tool-call.txt`：卡片 id 复用 `callID`、按 `callID` 就地更新（`STREAMING→RUNNING→COMPLETED`）、按事件到达顺序穿插在正文之间、执行终态收尾（成功/失败/中断）、`reset` 清空 |
| `OpenCodeRestClientUnitTest`（工具部分） | 单元（JUnit 4） | 助手 `content[]` 部件顺序解析（`reasoning` 跳过、`text`/`tool` 保序）；`completed/running/error/streaming` 四态；`input` 字符串（streaming）与对象（其余）；`content[].text` 与 `error.message` 兜底；`exit`/`truncated` |

> 前端 `ChatList` 的增量刷新与「发送/停止」切换依赖 Swing 组件，仓库暂无前端测试沙箱，由 §9.3 手工验收覆盖。

### 9.3 手工验收

逐字输出与首 token 时延、思考过程折叠、权限三按钮、工具卡片（运行中 → 完成/失败、退出码与输出截断标记）、断开 server 后自愈、长会话不卡顿。

约束：单测命名以 `UnitTest` 结尾、集成测试以 `ITest` 结尾，统一用 JUnit 4（项目既有栈），不引入 JUnit 5 平台监听。

## 10. 决议与遗留

**已决议**
1. 事件流连接层采用 `okhttp + okhttp-sse`（2026-09-29）；REST 保持 JDK 自带实现，不迁移到 OkHttp。
2. 推送复用既有 RPC `Flow`，不引入新的推送机制。
3. 帧分发按 `data` JSON 的 `type` 字段（服务端不发 `event:` 行），心跳注释帧单独处理。
4. 增量采用「delta 累积 + `ended` 全文校准」，不使用 `durable.seq` 做 delta 去重。
5. 真实验证与集成测试基准模型固定为 `opencode/mimo-v2.6-flash-free`（OpenCode Zen 免费模型）。

**遗留**
1. ~~**中断/取消链路未实测**~~ —— **已实测（2026-09-29）**：`session.interrupt` 后事件序列为 `reasoning.ended`（补全文）→ `step.streamed` → `step.failed(error.type=aborted, message="Step interrupted")` → `session.execution.interrupted`；**没有** `execution.failed/succeeded`。实现据此把 `aborted` 视为用户中断（不弹失败气泡），并以 `session.execution.interrupted` 作为执行态收尾依据（§4.3/§5.5），ITest 已覆盖。
2. 外部实例（桌面端启动）凭据不在 `service.json`，用该文件密码访问 401 —— 需在设置页显式配置凭据（观察项，见 §4.6）。
3. 工具输出已由 `session.tool.success.content[].text` 提供（卡片超过 500 行按既有 `CodeBlockPane` 规则截断显示，并标注「输出已截断」）；`shell.created.info.file`（host 上 `~/.local/share/opencode/shell/<projectID>/sh_*.out`）仅作超大输出截断后的补充读取参考，暂不实现。
4. REST 助手消息 `content[]` 的 `reasoning` 部件仍不渲染：切会话 / 对账后推理气泡会消失（正文与工具卡片不受影响）。如需保留，按 §4.7 同一套 parts 机制补一个 `Reasoning` 部件即可。
5. 工具失败态只有 REST `state.status=error` 有实测样本：SSE 侧未抓到「工具失败」事件（`session.tool.*` 无 failure 变体），卡片失败态依赖执行终态收尾 + REST 对账。

---

*文档结束*
# TSD-05 设置管理设计

> 插件：OpenCode AI Assistant Panel（`com.ayongw.idea.opencode-idea-panel`）
> 目标：在插件内管理 opencode 的设置参数（自定义模型、规则目录、技能、MCP），设置项能走接口的走接口，接口不满足的直接改 opencode 配置文件。
> 关联文档：整体架构见《技术方案》（`docs/tech/技术方案.md`，对应 TSD-01 段位）。
> 状态：**实施完成**（五个 Tab 全部落地），待 IDE 内手测与提交。

## 修订历史

| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-09-29 | 初始版本：现状盘点、通道矩阵、方案与变更清单 | agent |
| v1.1 | 2026-09-29 | 对齐代码变更：提交 `9f1b2fc` 已将插件全量迁移到 opencode v2（Basic 认证 + `/api` 前缀 + `{data}` 包裹、探测端点 `GET /api/project`、默认地址 `127.0.0.1:4096`）；据此更新结论、连接页描述、变更清单与风险 | agent |
| v1.2 | 2026-09-29 | 定稿设置项作用域默认值：**默认全局**，UI 可切换项目级 | agent |
| v1.3 | 2026-09-29 | 阶段一落地：新增 `JsoncEditor`（JSONC 定点编辑器）与 `OpenCodeConfigStore`（配置定位 / 读取 / 写入 / 备份 / 原子写 / 并发校验），配套 23 个单测通过；前端相关阶段暂缓以避让并行会话 | agent |
| v1.4 | 2026-09-29 | 实施完成：设置类接口（12 个）、`SettingsDtos`/`SettingsRpcApi`、`BackendSettingsRpcApi` + `SettingsMapping`、前端 5 个 Tab；52 个单测全绿、`buildPlugin` 通过。过程中修复两处既有缺陷：`HttpURLConnection` 不支持 PATCH（导致 `setShell` 与既有 `renameSession` 请求发不出去）、`JsonObject.get()` 缺键 NPE | agent |

---

## 1. 背景与目标

### 1.1 背景

插件当前只有一个应用级设置页（`opencode-frontend/.../settings/OpenCodeSettingsConfigurable.kt`），仅能配置 Server 地址与 Basic 认证凭据（用户名 + 密码）。opencode 自身有大量设置（模型、规则、技能、MCP 等），用户希望直接在插件里管理，避免手工编辑配置文件。

### 1.2 目标

1. 盘点 opencode 现有设置项，作为插件设置界面的字段来源。
2. 四类设置纳入管理：**自定义模型**、**规则目录**、**技能（skills）**、**MCP**。
3. 通道选择原则：能走 HTTP 接口的走接口，接口不满足的直接改配置文件。
4. 支持全局与项目两级配置作用域。

### 1.3 调研基准

运行态 opencode 版本 **v2.0.18**（源码 `/Users/jiangguangtao/workspace/opensource/opencode`，本地检出版本 v2.0.16）。下文接口行为均以本机实测为准，实测方式：`opencode serve --port 4598/4599` 后逐个调用。

插件侧代码基准：提交 `9f1b2fc`（refactor(api)：全量对齐 opencode v2）之后的 `OpenCodeRestClient`、`OpenCodeSettingsConfigurable`、`OpenCodeSettingsState`、`ChatRepositoryRpcApi`。

---

## 2. 结论先行

1. **opencode 设置几乎不能通过 HTTP 写入**。唯一能落盘的写接口是 `PATCH /api/experimental/config`，且只接受 `shell` 一个字段；MCP 的 PUT/DELETE/connect 是**运行时 override，不落盘**。因此四类设置**主体必须走直接修改配置文件**。
2. **配置文件是权威数据源**。实测 `GET /api/config` 返回的就是配置文件解析后的规范化结果（含文档路径），可直接用于回显与校验。
3. **改文件不需要重启**。opencode 对配置是热加载（file watch + `config.updated` + 各领域服务 reload）；必要时可补 `POST /api/location/reload`。
4. **HTTP 契约已对齐**（提交 `9f1b2fc`）。`OpenCodeRestClient` 现按 v2 契约实现：`/api` 前缀、响应 `{data:...}` 包裹、消息按 `type` 联合类型解析、连接探测走 `GET /api/project`（`/api/info` 仅提供版本等信息）。设置功能在此基线上扩展即可。
   - 依据：实测 v1 路径（`/global/health`、`/session`、`/global/event`）在 v2.0.18 上会回落到前端 SPA 的 HTML，**HTTP 状态却是 200**，据此做健康检查会误判、按 JSON 解析会失败；v2 真实接口全部挂在 `/api/*` 下。
5. **鉴权为 HTTP Basic**：用户名固定 `opencode`，密码来自设置页，未填写时回退环境变量 `OPENCODE_SERVER_PASSWORD`（后台 service 的密码另存于 `~/.config/opencode/service.json`）。同时支持 `?auth_token=<base64(用户名:密码)>`，**不是 Bearer**。

---

## 3. opencode 设置现状盘点

### 3.1 配置文件体系

| 文件 | 作用域 | 说明 |
|------|--------|------|
| `opencode.json` / `opencode.jsonc` | 全局 + 各级项目目录 | 服务端/运行时设置，支持 JSONC（注释、尾逗号） |
| `cli.json` | 仅全局 `~/.config/opencode/cli.json` | CLI/TUI 客户端设置（主题、键位、TUI 布局等） |

加载优先级（低 → 高）：wellknown 远程配置 < 全局目录 < `OPENCODE_CONFIG` 指定文件 < 项目各级 `opencode.json(c)`（越近越高）< 项目 `.opencode/opencode.json(c)` < `OPENCODE_CONFIG_CONTENT`。

全局目录解析顺序：`OPENCODE_CONFIG_DIR` → `$XDG_CONFIG_HOME/opencode` → `~/.config/opencode`。

配置支持变量替换：`{env:VAR}`、`{file:path}`（支持 `~/` 展开）。

### 3.2 配置字段清单（V2 顶层，真源 `packages/schema/src/config.ts`）

**A. 自定义模型（本次纳入）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `providers.<id>.canonical` | string | 继承哪个内置 provider 的目录默认值 |
| `providers.<id>.name` | string | 显示名 |
| `providers.<id>.env` | string[] | 有序的凭证环境变量名 |
| `providers.<id>.package` | string | 运行时包，如 `aisdk:@ai-sdk/openai-compatible`、`@opencode/ai/providers/anthropic`，也可是 npm 包或 `file://` |
| `providers.<id>.settings` | object | 通用键 `timeout`/`chunkTimeout`/`compaction`/`transport` + 包特定键 `baseURL`、`apiKey`、`region` 等（StructWithRest，允许任意额外键） |
| `providers.<id>.headers` | map | 附加请求头 |
| `providers.<id>.body` | map | 合并进请求体 |
| `providers.<id>.models.<modelID>` | object | `modelID`/`name`/`family`/`capabilities`/`limit{context,input,output}`/`cost`/`variants[]`/`disabled`/`settings`/`headers`/`body` |
| `model` | string \| object | 默认模型，`"provider/model#variant"` |
| `default_agent` / `agents.<name>.model` | string | 按 agent 指定模型 |

**B. 规则与上下文（本次纳入）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `instructions` | string[] | ⚠ **V2 只接受、不解析**，无运行时消费方；写 `~/.config/opencode/rules/*.md` 不会生效 |
| `AGENTS.md` | 文件 | **实际生效的规则载体**：全局 `~/.config/opencode/AGENTS.md` + 从工作目录向上到项目根的每级 `AGENTS.md`，合并（非覆盖）；V2 不再回退 `CLAUDE.md` |
| `skills` | string[] | 额外技能目录/URL，支持 `~/` 与 http(s)；自动发现 `~/.config/opencode/skills`、`.opencode/skills`、`~/.claude/skills` 等 |
| `references.<name>` | object | 命名本地目录/git 仓库（外部上下文），**不是规则目录** |

**C. MCP（本次纳入）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `mcp.servers.<name>`（local） | object | `type:"local"`、`command:string[]`、`cwd`、`environment`（字段名不是 `env`）、`disabled`、`codemode`、`timeout`、`protocol` |
| `mcp.servers.<name>`（remote） | object | `type:"remote"`、`url`、`headers`、`oauth{client_id,client_secret,scope,callback_port,redirect_uri,auth_server_metadata_url}` 或 `false`、`disabled`、`codemode`、`timeout`、`protocol` |
| `mcp.timeout` | object | `startup`（默认 30s）/`catalog`（30s）/`execution`（12h），单位毫秒 |

注：V1 写法 `mcp.<name>`（条目用 `enabled`）会被 normalize 自动转成 V2 的 `mcp.servers.<name>`（`enabled` 取反成 `disabled`）；持久化写入统一用 V2 形态。

**D. 其它技术项（本轮不纳入，后续按需扩展）**

`shell`、`update{disable,notify,auto}`（仅全局生效，需重启）、`share`、`snapshots`、`watcher.ignore`、`tool_output{max_lines,max_bytes}`、`media.image`、`compaction{auto,keep,buffer}`、`warming`、`worktree.directory`、`permissions[]{action,resource,effect}`、`commands{}`、`formatter`、`lsp`、`plugins[]`、`username`、`experimental{portable_shell_scanner,subagent_depth,policies[]}`。

### 3.3 现网配置现状

本机 `~/.config/opencode/opencode.jsonc` 仍是 **V1 写法**（`provider`、`skills.paths`、`mcp.<name>`、`disabled_providers`、`instructions`），v2.0.18 由 normalize 层兼容。实测 `GET /api/config` 返回的是**规范化后的 V2 形态**（`providers`、`skills` 数组、`mcp.servers`），说明读写两侧都可以按 V2 处理。

---

## 4. 读写通道矩阵（实测）

| 设置 | 读 | 写（持久化） | 生效方式 |
|------|----|-------------|---------|
| 全部配置文档 | `GET /api/config`（返回规范化 V2 + 文档 `path`），或直接读文件 | — | — |
| `shell` | `/api/config`、`/api/config/shell` | ✅ `PATCH /api/experimental/config`（唯一 HTTP 可写，仅全局） | 热加载 |
| providers / model | `/api/provider`、`/api/provider/{id}`、`/api/model`、`/api/model/default` | ❌ 无接口 → **改配置文件** | 热加载（provider.reload） |
| skills / references / instructions | `/api/skill`、`/api/reference` | ❌ 无接口 → **改配置文件** | 热加载（skill.reload） |
| MCP | `GET /api/mcp?location.directory=<dir>`（含 connected/failed 状态；**不带 location 参数返回空数组**） | ❌ 只有运行时 override → **改配置文件** | 改文件后 mcp.reload |
| apiKey 凭据 | `GET /api/integration`、`GET /api/integration/{id}`（`connections` 含 credential id/label/method） | ✅ `POST /api/integration/{id}/connect/key`（落 opencode SQLite 的 credential 表，持久） | 即时 |
| 插件包 | `/api/plugin` | `POST /api/plugin/update`（装包）；列表改动配置文件 | **需重启 server** |
| 手动重载 | — | `POST /api/location/reload`（可选） | — |
| 连接探测 / 服务信息 | `GET /api/project`（探测，项目列表；无需 location 参数）、`GET /api/info`（`{version,pid,urls,paths}`，启动中返回 503） | — | — |

实测样例：
- `GET /api/project` → 项目列表，可直接作为连接探测（插件已采用）
- `GET /api/config` → 返回 `[{type:"document",path:"/Users/…/opencode.jsonc",info:{…}},{type:"directory",path:"…/.config/opencode"}]`
- `GET /api/mcp` 不带参数 → `data:[]`；带 `location.directory` → `[{name:"codegraph",status:{status:"connected"}},…]`
- `GET /api/integration` → 含 `local`、`hello-tw` 各一条 `method:"key"` 的 credential

---

## 5. 方案设计

### 5.1 总体架构

沿用现有 split-mode 分层，**配置文件读写一律放 backend**（避免远端 frontend 进程访问不到配置文件的场景），frontend 只做 UI。

```
frontend  SettingsPanel（Tab 容器 + 5 个子面板，Swing）
    ↓  shared: SettingsRpcApi（新增）
backend   ├─ OpenCodeConfigStore    配置文件定位 / JSONC 读 / 路径 patcher / 备份 / 原子写
          └─ OpenCodeRestClient     v2 HTTP 读接口 + 凭据写入 + 重载（Basic 鉴权，已就位）
```

### 5.2 设置页 UI 结构

| Tab | 内容 | 读来源 | 写目标 |
|-----|------|--------|--------|
| 连接【已落地】 | Server URL、Basic 用户名（默认 `opencode`）、密码、测试连接；待补 `shell` 小项 | `GET /api/project`（测试连接）、`GET /api/config/shell` | 插件自身设置（`opencode-settings.xml`）；`shell` 优先走 `PATCH /api/experimental/config` |
| 模型 | provider 列表（id / name / package / `settings.baseURL` / models）增删改；默认模型下拉；每个 provider 的 apiKey | `/api/provider`、`/api/model`、`/api/model/default` | 配置文件 `providers.*`、`model`；apiKey 走 `connect/key` |
| 规则 | `AGENTS.md` 列表（全局 + 项目各级，标注存在性，可打开编辑）；`instructions` 列表（只读 + “V2 不生效”提示） | 文件系统 | `AGENTS.md` 文本读写；`instructions` 兼容增删 |
| 技能 | `skills` 目录/URL 列表增删；已发现技能只读清单 | `/api/skill` | 配置文件 `skills` |
| MCP | servers 表格（名称/类型/command 或 url/环境变量/启用/状态）增删改；`mcp.timeout` | `/api/mcp?location.directory=<项目>` | 配置文件 `mcp.servers.<name>`、`mcp.timeout` |

每项设置标注作用域（全局 / 项目），**默认全局**，切换作用域后重新定位目标文件。

### 5.3 配置文件读写（`OpenCodeConfigStore`）

**定位**：沿用 opencode 自身顺序，在目标作用域目录下依次探测 `opencode.json` → `opencode.jsonc` → `.opencode/opencode.json` → `.opencode/opencode.jsonc`；全不存在则按作用域默认新建 `opencode.json`（项目级）/ `opencode.jsonc`（全局级，与 core `Config.update` 行为一致）。

**读**：Gson lenient 模式解析 JSONC（兼容注释）。

**写（保格式局部 patch）**：只替换目标键的值区间，保留注释、缩进与未知字段，语义对齐 opencode 官方实现（`jsonc-parser` 的 `modify + applyEdits`，见 `packages/cli/src/commands/handlers/mcp/add.ts`）。写入路径白名单：

| JSON 路径 | 用途 |
|-----------|------|
| `providers.<id>` / `providers.<id>.settings.baseURL` / `providers.<id>.models.<mid>` | 模型配置 |
| `model` | 默认模型 |
| `skills` | 技能目录列表 |
| `mcp.servers.<name>` / `mcp.timeout` | MCP |
| `instructions` | 规则（兼容保留项） |

**安全措施**：写前 `.bak` 备份 → 原子写（tmp + rename）→ 写前比对 mtime 与内容，检测到外部修改则中止并要求重载；UI 提供改动 diff 预览。

**`AGENTS.md`** 不属于 JSON，走纯文本读写（追加/局部编辑），不做整文件覆盖。

### 5.4 RPC 接口（新增 `SettingsRpcApi`）

| 方法 | 作用 |
|------|------|
| `getSettingsSnapshot(projectId)` | 返回配置文档、providers/models、默认模型、skills、已发现技能、MCP servers+状态、可写字段当前值 |
| `saveProvider / removeProvider` | 写 `providers.<id>`、models |
| `setDefaultModel` | 写 `model` |
| `saveSkillPaths` | 写 `skills` |
| `saveMcpServer / removeMcpServer / setMcpTimeout` | 写 `mcp.servers.<name>`、`mcp.timeout` |
| `listRuleFiles / saveRuleFile / saveInstructions` | 读写 `AGENTS.md`、`instructions` |
| `saveCredential(integrationId, key)` | 调 `POST /api/integration/{id}/connect/key` |
| `setShell` | 优先 `PATCH /api/experimental/config`，失败回退写文件 |
| `previewConfigDiff` | 返回将要写入的文本 diff |

连接配置改造：`updateServerConfig(projectId, serverUrl, username, password)` **已完成**（提交 `9f1b2fc`，前端设置页 → `BackendChatRepositoryModel.updateServerConfig`）。新增设置类方法时沿用「只增不改」的兼容原则。

### 5.5 生效机制

| 设置 | 生效 |
|------|------|
| `shell`（走 API） | 即时（API 内部 `requestReload`） |
| providers / model / skills / instructions | 文件监听 → 热加载 |
| MCP | 文件监听 → mcp.reload |
| 凭据（apiKey） | 即时（写库） |
| plugins 列表 | 需重启 server（UI 需显式提示） |

写入后可选调用 `POST /api/location/reload` 兜底（文件监听失效时）。

---

## 6. 变更文件清单

| 文件 | 变更摘要 | 状态 |
|------|---------|------|
| `opencode-shared/.../SettingsDtos.kt` | 新增：Provider/McpServer/McpTimeout/Skill/ShellOption/RuleFile/SettingsSnapshot/WriteResult DTO（模型复用既有 `ModelDto`） | ✅ 已完成 |
| `opencode-shared/.../SettingsRpcApi.kt` | 新增：设置读写 RPC 契约（13 个方法） | ✅ 已完成 |
| `opencode-shared/.../ChatRepositoryRpcApi.kt` | 连接配置支持 Basic（`serverUrl` + `username` + `password`） | ✅ 已完成（`9f1b2fc`） |
| `opencode-backend/.../repository/OpenCodeRestClient.kt` | v2 前缀 + Basic 鉴权 + 探测 `GET /api/project`；新增 config/provider/model/mcp/skill/integration/reload/connect-key 方法；**PATCH 改走 JDK HttpClient** | ✅ 已完成 |
| `opencode-backend/.../repository/JsoncEditor.kt` | 新增：JSONC 定点编辑器（按 JSON 路径 patch，保留注释/缩进/未知字段） | ✅ 已完成 |
| `opencode-backend/.../repository/OpenCodeConfigStore.kt` | 新增：配置文件定位、JSONC 读取、路径 patch、备份、原子写、并发改动校验 | ✅ 已完成 |
| `opencode-backend/.../BackendSettingsRpcApi.kt` | 新增：实现 `SettingsRpcApi`（快照组装 + 定点写入；多键写入为单文件读改写） | ✅ 已完成 |
| `opencode-backend/.../SettingsMapping.kt` | 新增：配置 × 服务端目录 × 状态的纯映射（分端字段级合并、V1/V2 写法兼容）+ JSON 读取小工具 | ✅ 已完成 |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：暴露 `getRestClient()`（仅新增方法） | ✅ 已完成 |
| `opencode-backend/.../BackendRpcApiProvider.kt` | 修改：注册 `SettingsRpcApi` | ✅ 已完成 |
| `opencode-frontend/.../settings/OpenCodeSettingsConfigurable.kt` | 改为 Tab 容器（5 个 Tab） | ✅ 已完成 |
| `opencode-frontend/.../settings/SettingsTab.kt` | 新增：Tab 接口 + 面板基类（异步读取/写入、状态提示、作用域下拉） | ✅ 已完成 |
| `opencode-frontend/.../settings/ConnectionSettingsTab.kt` | 新增：连接面板（原表单迁入 + shell 选择） | ✅ 已完成 |
| `opencode-frontend/.../settings/{Provider,Rule,Skill,Mcp}SettingsTab.kt` | 新增：模型 / 规则 / 技能 / MCP 面板 | ✅ 已完成 |
| `opencode-frontend/.../settings/OpenCodeSettingsState.kt` | 未改动：`username`/`password` 由 `9f1b2fc` 落地；作用域默认全局在面板内置（不持久化上次选择） | — |
| `opencode-frontend/src/main/resources/messages/OpencodeFrontendBundle.properties` | 新增 `settings.opencode.*` 面板文案 | ✅ 已完成 |
| `opencode-frontend/src/main/resources/opencode-idea-panel.opencode-frontend.xml` | 无需改动（Configurable 类名与注册项不变） | — |
| `src/test/.../JsoncEditorUnitTest`、`OpenCodeConfigStoreUnitTest`、`OpenCodeSettingsApiUnitTest`、`SettingsMappingUnitTest` | 新增：52 个单测（其中设置相关 40 个） | ✅ 已完成 |

## 7. 实施顺序

1. backend 基建：`OpenCodeConfigStore` → `OpenCodeRestClient` 新增设置类接口 → DTO + RPC + 注册。
2. frontend 骨架：Tab 容器（连接表单迁入，并补 `shell` 小项）。
3. 业务 Tab：模型 / 技能 / MCP / 规则。
4. 收尾：凭据、i18n、单元测试。

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 会话链路契约漂移（v2 仍在演进） | 接口变更导致功能失效 | 已按 v2 对齐（`9f1b2fc`）；新增接口一律以 `GET /openapi.json` 为契约依据 |
| `instructions` 在 V2 不解析 | 用户改规则不生效 | 规则一律落 `AGENTS.md`；`instructions` 在 UI 标注“仅兼容保留” |
| V1/V2 写法混存 | 写入后配置风格不一致 | 统一按 V2 形态写入，不动历史字段 |
| 并发写同一配置文件 | 覆盖他人改动 | 写前 mtime+内容校验、`.bak` 备份、原子写 |
| JSONC 注释与格式 | 手工配置被破坏 | 仅做路径级 patch，保留注释与缩进 |
| server 不在本机 | 插件改不到其配置文件 | UI 明确提示“配置文件读写作用于本机”，远端场景仅提供只读视图 |
| `plugins` 改动需重启 | 用户误以为已生效 | UI 显式标注“需重启 server” |

## 9. 测试策略

| 用例 | 类型 | 覆盖点 |
|------|------|--------|
| JSONC 路径 patch | `*UnitTest` | 保留注释/缩进、新增键、覆盖键、删除键 |
| 配置文件定位 | `*UnitTest` | 四种候选文件优先级、都不存在时的默认新建路径 |
| Basic 鉴权头 | `*UnitTest` | 头格式、无密码时不带鉴权（已有 `OpenCodeRestClientUnitTest` 覆盖） |
| MCP 读写往返 | `*MockTest` | 写 `mcp.servers.<name>` 后回读一致、local/remote 两类 |
| 服务端接口 | `*MockTest` | `/api/mcp` 带 location 参数、`connect/key` 请求体、探测失败处理 |

测试风格沿用现有 `src/test/kotlin/com/ayongw/idea/opencode/OpenCodeRestClientUnitTest.kt`：本地 `com.sun.net.httpserver.HttpServer` + 路由表，断言真实请求（方法 / 路径 / Basic 头 / 请求体）与响应解析，不 mock HTTP 客户端。

> 本仓库存在并行会话同时改前端，前端半成品会使 Gradle 整体构建失败。此时只跑根项目单测可排除前端编译：`./gradlew :test --tests "com.ayongw.idea.opencode.*" -x :opencode-frontend:compileKotlin -x :opencode-frontend:processResources -x :opencode-frontend:instrumentCode -x :opencode-frontend:instrumentedJar`。

## 10. 决议与遗留

1. 【已定】设置项作用域默认值：**默认全局**（UI 可切换项目级）。
2. 【遗留】现有 `docs/tech/技术方案.md` 是否按《文档组织与命名规范》迁移为 `docs/tsd/TSD-01-整体方案.md`。

*文档结束*
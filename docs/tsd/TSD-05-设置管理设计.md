# TSD-05 设置管理设计

> 插件：OpenCode AI Assistant Panel（`com.ayongw.idea.opencode-idea-panel`）
> 目标：在插件内管理 opencode 的设置参数（自定义模型、规则目录、技能、MCP），设置项能走接口的走接口，接口不满足的直接改 opencode 配置文件。
> 关联文档：整体架构见《技术方案》（`docs/tech/技术方案.md`，对应 TSD-01 段位）。
> 状态：**实施完成**（五个 Tab 全部落地；v1.5 修复 401 与布局，v1.6 修复凭据明文落盘报错并收口内容宽度，v1.7 重做模型页与连接页并改为自动加载，v1.8 把技能/规则/MCP 改为「只读展示 + 打开配置文件/文件」形态，v1.9 兼容 V1 写法的供应商配置），待再次手测与提交。

## 修订历史

| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-09-29 | 初始版本：现状盘点、通道矩阵、方案与变更清单 | agent |
| v1.1 | 2026-09-29 | 对齐代码变更：提交 `9f1b2fc` 已将插件全量迁移到 opencode v2（Basic 认证 + `/api` 前缀 + `{data}` 包裹、探测端点 `GET /api/project`、默认地址 `127.0.0.1:4096`）；据此更新结论、连接页描述、变更清单与风险 | agent |
| v1.2 | 2026-09-29 | 定稿设置项作用域默认值：**默认全局**，UI 可切换项目级 | agent |
| v1.3 | 2026-09-29 | 阶段一落地：新增 `JsoncEditor`（JSONC 定点编辑器）与 `OpenCodeConfigStore`（配置定位 / 读取 / 写入 / 备份 / 原子写 / 并发校验），配套 23 个单测通过；前端相关阶段暂缓以避让并行会话 | agent |
| v1.4 | 2026-09-29 | 实施完成：设置类接口（12 个）、`SettingsDtos`/`SettingsRpcApi`、`BackendSettingsRpcApi` + `SettingsMapping`、前端 5 个 Tab；52 个单测全绿、`buildPlugin` 通过。过程中修复两处既有缺陷：`HttpURLConnection` 不支持 PATCH（导致 `setShell` 与既有 `renameSession` 请求发不出去）、`JsonObject.get()` 缺键 NPE | agent |
| v1.5 | 2026-09-29 | 手测反馈修复：① 新增 `OpenCodeCredentials`，密码留空时按「显式值 → `OPENCODE_SERVER_PASSWORD` → `~/.config/opencode/service.json`」兜底，消除设置页 401；② 错误体截断为 200 字符 + 前端按 401/403/不可达转友好文案（原样贴整段 JSON 的写法移除）；③ 全部面板改 `BorderLayout` 自适应布局、去掉固定 `columns`/`preferredSize`，内容不再超宽；④ 技能页改为「加载目录 + 已加载技能列表」，MCP 页改为「配置来源 + 服务器列表 + 详情/超时」；⑤ 测试连接改走后端真实凭据探测，不再由前端自行拼 Basic 误判；⑥ 新增 `OpenCodeCredentialsUnitTest`（4 例），单测合计 56 例全绿 | agent |
| v1.6 | 2026-09-29 | 修复安装后报错 `Element component@OpenCodeSettings.option.@name=password probably contains sensitive information`：密码从插件设置文件（明文）迁到 IDE 凭据存储 —— 新增 `OpenCodePasswordStore`（`PasswordSafe` + 内存缓存），`OpenCodeSettingsState` 不再持有密码字段，连接页与启动时的配置下发改从凭据存储取值。另：设置页内容宽度收口（表格/多行文本 preferred 宽度上限 560、文本框限定 `columns`、状态行截断 100 字），容器实现 `Scrollable`（`tracksViewportWidth`）使页面宽度跟随对话框、不再横向溢出 | agent |
| v1.7 | 2026-09-29 | 按手测反馈重做设置页（详见 §5.2/§5.4）：① 明确两类设置——插件自身设置（连接页，存 IDEA）与 opencode 设置（模型/规则/技能/MCP，写配置文件）；② 连接页只留 URL/用户名/密码，去掉 shell 界面（后端 `setShell` 接口保留）；③ 模型页重做为 master-detail：默认模型置顶 → 供应商表（id/名称/是否自定义 + 行内「设置」按钮）→ 选中供应商的模型表（id/名称/启用勾选，自定义供应商可增删），去掉作用域选择；模型状态改为「配置声明（含 `disabled`）∪ `/api/model` 启用清单」并集，写入一律按键 patch（不再整体覆盖 `models`）；④ 设置页改为首次显示与切换 Tab 自动加载，并对「服务端未就绪导致的静默空结果」自动重试 | agent |
| v1.8 | 2026-09-29 | 按手测反馈把技能/规则/MCP 三页改为「只读展示 + 打开文件」形态（详见 §5.2）：① 技能页＝加载来源（配置声明 + opencode 约定目录，标注存在性）+ 技能卡片（第一行 id、第二行描述、齿轮跳转技能目录）+ 搜索过滤；② 规则页＝加载位置（AGENTS.md 目录 + `instructions` 条目）+ 规则文件卡片（文件名 + 前 150 字符、齿轮在编辑器打开），移除内嵌编辑器；③ MCP 页＝卡片列表（名称 + 状态徽标 + 启用开关 + 齿轮打开配置文件），移除详情表单、增删服务器与 `mcp.timeout` 编辑；④ 修正 `skills` 只读字符串数组导致用户 `{paths,urls}` 写法被漏展示的缺陷；⑤ 配置定位改为同目录 `opencode.jsonc` 优先、缺省新建 `opencode.jsonc`（对齐 opencode `Config.loadDirectory`/`Config.update`）；⑥ 新增 `ensureConfigFile` 与前端「在编辑器打开 / 跳转目录」能力 | agent |
| v1.9 | 2026-09-29 | 兼容 V1 写法的供应商配置（详见 §5.2/§5.3/§5.4）：opencode 同时接受 V2 `providers`（`package`/`settings.baseURL`/模型 `disabled`）与 V1 `provider`（`npm`/`options.baseURL`，`api` 优先；模型用 `status:"deprecated"` 表达禁用），此前只读 V2 导致 V1 配置的供应商在模型页显示为空、且无法增删模型。现按 `normalize.ts` 的 `migrateProviders`/`mergeMaps` 口径读两侧（同名条目 V2 覆盖 V1、V1 历史 id 改名），写入位置与键名跟随条目现有写法（避免造出并存的 V2 条目），包名统一按 V2 的 `aisdk:` 形式展示 | agent |

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
| 连接【已落地】 | **插件自身设置**：标题 + 刷新；Server URL、Basic 用户名（默认 `opencode`）、密码（附「留空即回退环境变量 / `service.json`」提示）、测试连接。不含 shell | 插件状态（`OpenCodeSettingsState` + `OpenCodePasswordStore`）；测试连接走后端 `updateServerConfig` + `getAllSessions` + `getServerInfo` | IDE 侧：`opencode-settings.xml` 存 URL/用户名，**密码存 IDE 凭据存储**（`OpenCodePasswordStore`，v1.6）。本页由「OK」统一提交（`isModified`/`apply`） |
| 模型【v1.7 重做 / v1.9 兼容 V1】 | **opencode 设置**（无作用域选择）：上=默认模型下拉 + 保存；中=供应商表（id / 名称 / 是否自定义 + 行内「设置」按钮 → 弹窗改名称、连接 URL、API Key；工具栏可新增/删除供应商）；下=选中供应商的模型表（模型 id / 名称 / 启用勾选；自定义供应商可新增/删除模型） | `/api/provider`、`/api/model` + 配置 `providers.*`（V2）**与 `provider.*`（V1）都读**（`ProviderModelDto` = 配置声明（含禁用状态）∪ 服务端启用清单） | 按键 patch；路径与键名**跟随条目的现有写法**：V2 写 `providers.<id>.package` / `.settings.baseURL` / `.models.<mid>.disabled`，V1 写 `provider.<id>.npm` / `.options.baseURL` / `.models.<mid>.status="deprecated"`。apiKey 走 `connect/key`。作用域由该供应商的声明作用域决定（未声明过则全局），界面不暴露 |
| 规则【v1.8 重做】 | **opencode 设置**：上=规则加载位置（`AGENTS.md` 所在目录 + 配置 `instructions` 条目，标注「v2 未消费」）；下=已加载规则文件卡片（文件名 + 文件前 150 字符，齿轮在编辑器中打开） | 文件系统（`ruleFiles` 带 `preview`）+ 配置 `instructions` | 不在设置页内编辑，一律打开 `AGENTS.md` 直接改 |
| 技能【v1.8 重做】 | **opencode 设置**：上=技能加载来源（配置声明的路径/URL + opencode 约定目录 `skill`/`skills`，标注存在性）+「打开配置文件」；下=已加载技能卡片（第一行 `id`、第二行描述，齿轮跳转到技能所在目录）+ 关键词过滤 | 配置 `skills`（**字符串数组与 `{paths,urls}` 对象两种写法都读**）+ 约定目录 + `/api/skill` | 不在设置页内编辑，一律打开 opencode 配置文件改 |
| MCP【v1.8 重做】 | **opencode 设置**：配置来源行 + 「打开配置文件」；卡片列表＝名称 + 状态徽标 + 启用开关 + 齿轮打开配置文件（无描述字段，故不显示第二行） | 配置 `mcp.servers` + `/api/mcp` | 启用/禁用写 `mcp.servers.<name>.disabled`（复用 `saveMcpServer`，保留条目内其它键）；增删改一律编辑配置文件 |

所有面板统一布局约定：`BorderLayout` 分区（标题行 → 内容区 → 状态行），不使用固定 `columns`/`preferredSize`，内容随设置窗口拉伸；状态行只显示友好文案（401/403/不可达等），原始错误放 tooltip。

**加载时机（v1.7）**：设置页打开时只加载必需页（连接页，`SettingsTab.eager = true`）与当前选中页，切换 Tab 时加载该页；`loadSnapshot` 对「无告警但服务端清单全空」的结果自动重试（最多 3 次、间隔 800ms），避免「必须先点刷新」；同一面板同时只保留一个在途快照请求。

除模型页外，其余写配置文件的页仍按 §5.2 标注作用域（全局 / 项目），**默认全局**；v1.8 起界面不再暴露作用域选择，写入作用域一律由「该条目在哪个配置文件里声明」推断（未声明过则全局）。

### 5.3 配置文件读写（`OpenCodeConfigStore`）

**定位**：沿用 opencode 自身顺序，在目标作用域目录下依次探测 `opencode.jsonc` → `opencode.json` → `.opencode/opencode.jsonc` → `.opencode/opencode.json`（同一目录内 **jsonc 覆盖 json**，见 `Config.loadDirectory` 按 `["opencode.json","opencode.jsonc"]` 顺序加载）；全不存在则新建 `opencode.jsonc`（与 `Config.update` 的回退一致，不区分作用域）。

**读**：Gson lenient 模式解析 JSONC（兼容注释）。

**写（保格式局部 patch）**：只替换目标键的值区间，保留注释、缩进与未知字段，语义对齐 opencode 官方实现（`jsonc-parser` 的 `modify + applyEdits`，见 `packages/cli/src/commands/handlers/mcp/add.ts`）。写入路径白名单：

| JSON 路径 | 用途 |
|-----------|------|
| `providers.<id>` / `providers.<id>.settings.baseURL` | 供应商（V2 写法） |
| `provider.<id>` / `provider.<id>.npm` / `provider.<id>.options.baseURL` | 供应商（V1 写法，v1.9 兼容；条目已按 V1 声明时写回这一侧） |
| `models.<mid>` / `.name` / `.disabled`（V2）或 `.status="deprecated"`（V1） | 模型增删改与启用状态（一律写叶子键，避免覆盖同层 `limit`/`capabilities`） |
| `model` | 默认模型 |
| `skills` | 技能目录列表 |
| `mcp.servers.<name>` / `mcp.timeout` | MCP |
| `instructions` | 规则（兼容保留项） |

**安全措施**：写前 `.bak` 备份 → 原子写（tmp + rename）。`OpenCodeConfigStore.patch(..., expectedText)` 具备「比对读取时文本」的并发校验（不一致抛 `ConfigModifiedException`），但当前 RPC 写入路径未启用该参数，也无 diff 预览，见 §10 遗留。

**`AGENTS.md`** 不属于 JSON，走纯文本读写：读取全文 → 编辑器整篇保存（写前 `.bak` 备份 + 原子写），不做 JSON 路径 patch。

### 5.4 RPC 接口（新增 `SettingsRpcApi`）

| 方法 | 作用 |
|------|------|
| `getSnapshot(projectId)` | 一次性返回设置页所需全部读数据（配置路径、providers（含模型清单，V1/V2 两种写法都解析）/默认模型、技能加载来源 + 已发现技能、MCP servers + 状态 + timeout、shell/shells、instructions、`AGENTS.md` 候选（带 `preview`）、warnings） |
| `saveProvider / removeProvider` | 写 / 删供应商（名称、包、`baseURL`）。**不写 `models`**（v1.7：整体覆盖会丢 `limit`/`capabilities` 等字段）；v1.9：目标容器与键名跟随条目现有写法（V1 写 `provider.<id>.npm` / `.options.baseURL`） |
| `setDefaultModel` | 写 `model` |
| `setProviderModelEnabled`（v1.7） | 启用/禁用模型：V2 写 `providers.<id>.models.<mid>.disabled`（禁用 `true`、启用删键），V1 写 `provider.<id>.models.<mid>.status = "deprecated"`（v1.9） |
| `saveProviderModel`（v1.7） | 新增/改名模型：只写 `...models.<mid>.name`，条目缺失时自动补出 |
| `removeProviderModel`（v1.7） | 删 `...models.<mid>`（仅配置声明过的模型可删） |
| `saveSkills` | 写 `skills`（v1.8 起界面不再调用，保留接口） |
| `ensureConfigFile`（v1.8） | 目标作用域配置文件缺失时建立空 `{}`，供「打开配置文件」入口使用 |
| `saveMcpServer / removeMcpServer / saveMcpTimeout` | 写 `mcp.servers.<name>`、`mcp.timeout`（保留条目内未覆盖的键）；v1.8 起界面只用 `saveMcpServer` 切换 `disabled`，增删与 timeout 编辑改由用户直接编辑配置文件 |
| `saveInstructions` | 写 `instructions`（兼容保留项） |
| `readRuleFile / saveRuleFile` | 读写任意文本文件（v1.8 起界面不再内嵌编辑规则，改为在编辑器打开；接口保留） |
| `saveCredential(integrationId, key)` | 调 `POST /api/integration/{id}/connect/key` |
| `setShell` | 优先 `PATCH /api/experimental/config`，失败回退写文件 |
| `reloadConfig` | 调 `POST /api/config/reload` 兜底 |

写接口统一返回 `SettingsWriteResultDto`（失败不抛异常）。**凭据发现**（v1.5 新增）：`OpenCodeCredentials.resolvePassword(explicit)` 按「显式值 → `OPENCODE_SERVER_PASSWORD` → `~/.config/opencode/service.json` 的 `password`」解析，`BackendChatRepositoryModel` 初始密码与 `updateServerConfig` 均走它，密码留空不再把兜底覆盖成空串。

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
| `opencode-shared/.../SettingsDtos.kt` | 新增：Provider/McpServer/McpTimeout/Skill/SkillSource/ShellOption/RuleFile/SettingsSnapshot/WriteResult DTO；v1.7：`ProviderDto.models` 由 `List<ModelDto>` 改为 `List<ProviderModelDto>`（id/name/disabled/declaredInConfig），新增 `custom` 标记，快照去掉已无用的 `models` 字段；v1.8：快照 `skills` 改为 `skillSources`（含 `declared`/`exists`），`RuleFileDto` 增加 `preview` | ✅ 已完成 |
| `opencode-shared/.../SettingsRpcApi.kt` | 新增：设置读写 RPC 契约（v1.7 增 3 个模型写方法；v1.8 增 `ensureConfigFile`，共 17 个方法） | ✅ 已完成 |
| `opencode-shared/.../ChatRepositoryRpcApi.kt` | 连接配置支持 Basic（`serverUrl` + `username` + `password`） | ✅ 已完成（`9f1b2fc`） |
| `opencode-backend/.../repository/OpenCodeRestClient.kt` | v2 前缀 + Basic 鉴权 + 探测 `GET /api/project`；新增 config/provider/model/mcp/skill/integration/reload/connect-key 方法；**PATCH 改走 JDK HttpClient** | ✅ 已完成 |
| `opencode-backend/.../repository/JsoncEditor.kt` | 新增：JSONC 定点编辑器（按 JSON 路径 patch，保留注释/缩进/未知字段） | ✅ 已完成 |
| `opencode-backend/.../repository/OpenCodeConfigStore.kt` | 新增：配置文件定位、JSONC 读取、路径 patch、备份、原子写、并发改动校验；v1.8：候选顺序改为 jsonc 优先、缺省新建 `opencode.jsonc`（对齐 opencode），并公开 `expandUserPath` 供技能来源判存在 | ✅ 已完成 |
| `opencode-backend/.../BackendSettingsRpcApi.kt` | 新增：实现 `SettingsRpcApi`（快照组装 + 定点写入；多键写入为单文件读改写）；v1.7：实现 3 个模型写方法（按键 patch）、`saveProvider` 不再写 `models`；v1.8：快照新增技能加载来源与规则文件预览，新增 `ensureConfigFile`；v1.9：供应商与模型的写入按 V1/V2 方言选择容器与键名 | ✅ 已完成 |
| `opencode-backend/.../SettingsMapping.kt` | 新增：配置 × 服务端目录 × 状态的纯映射（字段级合并、V1/V2 写法兼容）+ JSON 读取小工具；v1.7：`providers` 增加 `liveModels` 入参，模型清单改为「配置声明（含 `disabled`）∪ 服务端启用清单」并集，并按配置声明判定 `custom`/`declaredInConfig`；v1.8：新增 `skillPaths`（兼容字符串数组与 `{paths,urls}` 对象）；v1.9：供应商改为同时读 V2 `providers` 与 V1 `provider`（`providerConfigs`/`providerPackage`/`providerBaseUrl`/`providerWriteTarget`） | ✅ 已完成 |
| `opencode-backend/.../repository/OpenCodeCredentials.kt` | 新增（v1.5）：Basic 密码发现（显式值 → 环境变量 → `service.json`），消除设置页 401 | ✅ 已完成 |
| `opencode-backend/.../BackendChatRepositoryModel.kt` | 修改：暴露 `getRestClient()`；初始密码与 `updateServerConfig` 改走 `OpenCodeCredentials.resolvePassword` | ✅ 已完成 |
| `opencode-backend/.../BackendRpcApiProvider.kt` | 修改：注册 `SettingsRpcApi` | ✅ 已完成 |
| `opencode-frontend/.../settings/OpenCodeSettingsConfigurable.kt` | 改为 Tab 容器（5 个 Tab）；v1.7：连接页 `eager` 常驻加载、切换 Tab 自动加载该页 | ✅ 已完成 |
| `opencode-frontend/.../settings/SettingsTab.kt` | Tab 接口 + 面板基类：标题/说明行、统一表格样式、**状态行（友好文案 + tooltip 明细）**、401/403/不可达文案转换、异步读取/写入骨架；v1.7：新增 `eager` 标记、快照空结果自动重试、在途请求去重；v1.8：新增齿轮按钮与「在编辑器打开文件 / 跳转目录 / 打开配置文件」能力，移除已无用的作用域行 | ✅ 已完成 |
| `opencode-frontend/.../settings/SettingsCards.kt` | 新增（v1.8）：卡片列表组件（第一行标题 + 第二行折行副标题 + 右侧操作区，横向跟随视口、内部纵向滚动）与关键词过滤、HTML 折行标签、路径/截断工具 | ✅ 已完成 |
| `opencode-frontend/.../settings/ConnectionSettingsTab.kt` | 插件自身设置面板：Server URL / 用户名 / 密码 + 密码留空提示 + 测试连接（走后端真实凭据探测），密码读写走 `OpenCodePasswordStore`；v1.7：移除 shell 界面（后端 `setShell` 接口保留） | ✅ 已完成 |
| `opencode-frontend/.../settings/ProviderSettingsTab.kt` | v1.7 全面重做：默认模型置顶 → 供应商表（id/名称/是否自定义 + 行内「设置」按钮 → 名称/连接 URL/API Key 弹窗；工具栏新增/删除供应商）→ master-detail 模型表（id/名称/启用勾选，自定义供应商可增删模型）；去掉作用域选择，写作用域跟随供应商声明作用域 | ✅ 已完成 |
| `opencode-frontend/.../settings/RuleSettingsTab.kt` | v1.8 重写：规则加载位置（AGENTS.md 目录 + `instructions` 条目）+ 规则文件卡片（文件名 + 前 150 字符 + 齿轮编辑器打开）；移除内嵌编辑器与保存/Reload | ✅ 已完成 |
| `opencode-frontend/.../settings/SkillSettingsTab.kt` | v1.8 重写：技能加载来源（配置声明 + 约定目录，标注存在性）+ 「打开配置文件」+ 技能卡片（id + 描述 + 齿轮跳转目录）+ 关键词过滤；移除可编辑目录文本域 | ✅ 已完成 |
| `opencode-frontend/.../settings/McpSettingsTab.kt` | v1.8 重写：卡片列表（名称 + 状态徽标 + 启用开关 + 齿轮打开配置文件）+ 配置来源行；移除详情表单、增删服务器、`mcp.timeout` 编辑 | ✅ 已完成 |
| `opencode-frontend/.../settings/OpenCodeSettingsState.kt` | 修改（v1.6）：移除密码字段 —— 明文凭据落 `opencode-settings.xml` 会被 IDE 判为敏感信息并报 error；`username` 由 `9f1b2fc` 落地；作用域默认全局在面板内置（不持久化上次选择） | ✅ 已完成 |
| `opencode-frontend/.../settings/OpenCodePasswordStore.kt` | 新增（v1.6）：Basic 密码存取（`PasswordSafe` 凭据存储 + 内存缓存，读写失败不阻塞），供连接页与启动配置下发使用 | ✅ 已完成 |
| `opencode-frontend/src/main/resources/messages/OpencodeFrontendBundle.properties` | 新增 `settings.opencode.*` 面板文案；v1.7：补模型页/供应商弹窗文案；v1.8：补技能/规则/MCP 卡片与「打开配置文件」文案，移除已下线的 shell、旧 provider 表单、MCP 详情/超时、作用域下拉文案 | ✅ 已完成 |
| `opencode-frontend/src/main/resources/opencode-idea-panel.opencode-frontend.xml` | 无需改动（Configurable 类名与注册项不变） | — |
| `src/test/.../JsoncEditorUnitTest`、`OpenCodeConfigStoreUnitTest`、`OpenCodeSettingsApiUnitTest`、`SettingsMappingUnitTest`、`OpenCodeCredentialsUnitTest` | 全仓 109 个单测，其中设置相关 57 个（v1.7 补模型并集映射 2 例、模型按键 patch 4 例；v1.8 补 `skills` 写法兼容 2 例，并订正配置定位优先级断言；v1.9 补供应商 V1/V2 兼容 5 例） | ✅ 已完成 |

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
2. 【遗留】写入路径未启用 `expectedText` 并发校验，也无改动 diff 预览（`previewConfigDiff` 未实现）。
3. 【遗留】现有 `docs/tech/技术方案.md` 是否按《文档组织与命名规范》迁移为 `docs/tsd/TSD-01-整体方案.md`。

*文档结束*
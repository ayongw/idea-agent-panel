# TSD-31 进程与连接管理方案

> 定位：本文交付 G4「进程与连接管理」的可独立执行方案——插件自己以子进程拉起并守护 `opencode serve`，含发现与复用、就绪探测、优雅终止、自愈与 UI 反馈。
> 关系：本文**取代**《TSD-30-会话面板整体优化方案》§9.2 中 G4 一行的「建议单独立项（待立项）」状态；REST/SSE 契约沿用《TSD-06-事件流接入设计》§4（本文不改其契约，只改「地址与凭据从哪来」）；设置页形态见《TSD-05-设置管理设计》；Tool Window 布局见《TSD-07-主界面布局设计》§1。
> 核实基线：本机 SDK = IntelliJ IDEA **2026.2.3**（build `262.10968.63`，`/Applications/IntelliJ IDEA.app/Contents/lib`）；`opencode` CLI = **v2.0.18**。§12 为逐条 API 实证结果。

## 修订历史
| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-09-30 | 初版：现状证据 + 目标/非目标 + 生命周期状态机 + 发现复用与端口策略 + 就绪探测与失败分类 + 进程启动与优雅终止 + 自愈 + 连接凭据 + UI 反馈 + 模块边界 + 风险 + T1~T10 任务拆分 + 验收与测试方案 + 参考来源 + 待拍板决策点 | agent |
| v1.1 | 2026-09-30 | 按用户决策定稿：默认自动启动 + 归属识别 + 他人实例密钥接入 + CLI 引导 + 引用计数归属 + 端口 4096/备用与探测顺序；§12 改为 API 核实结果（含 2026.2.3 SDK 实证） | agent |
| v1.2 | 2026-09-30 | T1~T4/T9/T10 落地后的实证修正：① 进程管理类实际来自**公开模块** `intellij.platform.util`（`util.jar`），**不得**声明 internal 的 `intellij.platform.execution`（沙箱会拒绝加载插件）；② 探测传输由 `HttpRequests` 改为 JDK `HttpURLConnection` + `Proxy.NO_PROXY`（`HttpRequests` 属 `intellij.platform.ide.core`，同样会扩张内部模块依赖面）；③ CLI 路径解析改为「显式配置不可用即报错」不回落 PATH | agent |

---

## 1. 背景与现状

### 1.1 产品诉求（已核实）

《产品说明》§4.1「进程管理：启动与复用 OpenCode Server」明确要求：插件启动时以子进程方式拉起 `opencode serve`，用 `OSProcessHandler` 管理；分配端口 → 建 `GeneralCommandLine` → `startNotify()` → 轮询 `GET /api/info` 等待就绪；启动前先健康检查，已有 Server 则复用；`dispose()` 中终止自有进程，确保 Windows 不残留文件锁定。

《产品说明》§五 风险表把「Server 启动失败」（插件不可用 → 提供手动配置入口 + 健康检查 + 重试）与「Windows 进程残留」（文件锁定导致插件更新失败）列为两项独立风险。

《产品说明》§二 技术选型表已把「进程管理 = `OSProcessHandler`」定为决策；`README.md:25` 与 `README.md:158` 也各自声明了「自动启动/复用 `opencode serve`」与「`OSProcessHandler` + 端口 + 健康检查轮询」，即**该能力已被写进对外文档，但代码为零实现**。

> **复核修正（v1.1，依据 §12 实证）**：
> ① 端口策略由「随机端口优先」改为「新实例优先 4096、冲突走备用端口」，探测顺序固定为「默认端口 4096 → 备用端口」（见 §3.4）。
> ② 「未就绪返回 503」在 `opencode` v2.0.18 的 OpenAPI 契约中**未声明**（`/api/info` 仅 200/400/401），就绪判定不得依赖 503（见 §3.5、§12 A14）。
> ③ PRD 所称「`killProcessTree()`」的实际入口是 `OSProcessHandler` 的 **`protected` 方法**，外部不可直接调用；须改用 `KillableProcessHandler` + 递归销毁开关（见 §3.7、§12 A3）。

### 1.2 现状（零实现）与证据

| 项 | 现状 | 证据 |
|---|---|---|
| 进程管理 | **全仓零实现**：无 `OSProcessHandler` / `GeneralCommandLine` / 进程终止任何引用 | 全仓 grep 无命中；`docs/tasks/001 环境初始化.md:186` 仅出现在文档示例里 |
| Server 从哪来 | 由用户**手动**启动 `opencode serve`，再在设置页手填地址 | `docs/tsd/TSD-30-会话面板整体优化方案.md:358`（G4 行）；`README.md:60` 要求「已安装并可在 PATH 中找到 `opencode` CLI」 |
| 地址配置 | 应用级 `PersistentStateComponent`：`serverUrl` 默认 `http://127.0.0.1:4096`、`username` 默认 `opencode` | `opencode-frontend/src/main/kotlin/com/ayongw/idea/opencode/frontend/settings/OpenCodeSettingsState.kt:20,23` |
| 密码配置 | 走 `PasswordSafe`（不进插件设置文件，避免 IDE 判敏感信息） | `settings/OpenCodePasswordStore.kt:20`（`CredentialAttributes`）、`:26-39`（`load`/`save`） |
| 地址下发 | 设置页「OK」把三项下发到后端，后端重建 REST 客户端与事件流 | `settings/ConnectionSettingsTab.kt:74-92` → `BackendChatRepositoryModel.kt:665-683`（`updateServerConfig`） |
| 认证 | HTTP Basic（用户名默认 `opencode`，密码非空才附加头） | `opencode-backend/src/main/kotlin/com/ayongw/idea/opencode/backend/repository/OpenCodeAuth.kt:15-19` |
| 密码发现链 | 显式值 → `OPENCODE_SERVER_PASSWORD` → `~/.config/opencode/service.json` | `repository/OpenCodeCredentials.kt:16-24,34` |
| 健康检查 | `GET /api/project`（注释说明 v2 无 `/global/health`），另有 `GET /api/info`（返回 version/pid/urls/paths） | `repository/OpenCodeRestClient.kt:43`（`healthCheck`）、`:414`（`getInfo`） |
| 事件流连接 | okhttp-sse，指数退避重连（1s→30s）、401/403 停止重连并交设置页、读超时 60s 判链路死 | `backend/event/OpenCodeEventClient.kt:25-34,72-84,117-139` |
| 服务模式 | `@Service(Service.Level.PROJECT)` + `Disposable`，`dispose()` 关事件流 + 取消协程 | `BackendChatRepositoryModel.kt:43-44,116-122,852-856` |
| 后端模块 | `opencode-backend` 走 `intellij.platform.module`，有 `remoteApiProvider` 注册；`splitMode = true`、`pluginInstallationTarget = BOTH` | `opencode-backend/src/main/resources/opencode-idea-panel.opencode-backend.xml:6-14`、`build.gradle.kts:117-118` |
| 最低平台 | `sinceBuild = "262"`（2026.2），本地 SDK 为 2026.2.3 | `build.gradle.kts:120-125`、`README.md:106` |
| 现有测试约束 | `./gradlew test` 默认 `exclude("**/*ITest.class")`，`-Pit=true` 才纳入 | `build.gradle.kts:152-158` |

### 1.3 缺口清单

| # | 缺口 | 后果 |
|---|---|---|
| N1 | 无端口分配、无进程拉起 | 用户必须自己装 CLI、自己起 server，「开箱可用」不成立 |
| N2 | 无就绪探测 | 用户填了地址但 server 没起时，面板只能表现为「REST 全失败 + 回退模拟响应」（`BackendChatRepositoryModel.kt:367-371,395-398`） |
| N3 | 无「自有实例」识别 | 无法区分「本插件拉的 server」与「用户/桌面端拉起的 server」，因此无法安全复用、更无法安全终止 |
| N4 | 无优雅终止 | 插件退出后子进程存活（若实现不当），Windows 上文件锁定会阻塞插件升级（PRD §五） |
| N5 | 无进程级自愈 | `OpenCodeEventClient` 只负责重连（`:130-139`），server 进程死了它只会无限退避重连，不会拉起 |
| N6 | 无状态外显 | Tool Window 无「启动中/失败/可重试」状态位，PRD §4.1 要求的错误态与手动配置入口无处安放 |

---

## 2. 目标与非目标

### 2.1 目标

1. **开箱可用（默认自动启动）**：项目打开（`S1` 初始化）时即探测；若本机无可用 Server，插件**默认**自行以子进程拉起 `opencode serve` 并等待就绪；设置页可关闭该默认行为。
2. **安全复用（含他人实例）**：优先复用已存在的可用实例；**他人启动的实例允许接入，但需要用户输入对应密钥**（交互成本可接受）。
3. **可控终止（引用计数归属）**：插件自己拉起的进程由**共享注册表上的引用计数**管理——同一 IDE 多工作区/多窗口共享同一 server，**单个引用者关闭不终止**，**最后一个引用者关闭才终止**；**绝不**终止非自己拉起的进程。
4. **可诊断**：启动失败有明确分类（CLI 缺失 / 端口占用 / 认证失败 / 超时），Tool Window 内可见、可重试、可跳设置。
5. **CLI 未安装可引导**：识别 `CLI_NOT_FOUND` 后**提醒并引导安装**（通知 + 打开文档/官网 + 设置页可填 CLI 路径），**不**代用户安装。
6. **自愈**：自有进程意外退出后按上限与退避重启；超出上限转 `FAILED` 并交人工。
7. **可测**：状态机、地址解析、端口策略、失败分类、输出缓冲、引用计数为纯逻辑，可脱离 IDE 单测。

### 2.2 非目标（明确排除）

- **不改《TSD-06-事件流接入设计》的 REST/SSE 契约**：端点、帧格式、事件名、对账语义一律不动；本文只改变「`baseUrl` / `username` / `password` 从哪来」。
- **不引入新三方依赖**：只用平台 API + JDK；HTTP 探测改用平台自带的 `com.intellij.util.io.HttpRequests`（见 §3.5），不把 okhttp 引入进程管理链路。
- **不做跨机器/远程部署方案**：不实现「在远端主机安装并启动 opencode」；split mode 与远程开发下仅做**安全降级**（见 §7 R4）。
- **不做 Server 版本升级/安装器**：**不**代用户安装 `opencode`，只做「提醒 + 引导 + CLI 路径覆盖」，不做版本自愈升级（G5 版本适配属另一议题，见《TSD-30-会话面板整体优化方案》§9.2 G5）。
- **不改设置页的既有三项（地址/用户名/密码）语义与存储位置**（地址仍入 `OpenCodeSettingsState`、密码仍入 `PasswordSafe`），仅**新增** Server 管理相关项。
- **不做多 Server 池化/负载均衡**：一个 Project 一个活动 Server 端点。
- **不引入 `verifyPlugin` 到本地流程**（与项目约束一致）。

---

## 3. 总体设计

### 3.1 分层与职责

新增一层「Server 运行时」置于既有连接层之下，对上层只暴露「一个当前端点 + 一个状态流」。

```mermaid
flowchart TB
  classDef std fill:#e3f2fd,color:#000000,stroke:#000000
  classDef emph fill:#1976d2,color:#ffffff,stroke:#000000
  classDef chg fill:#c8e6c9,color:#000000,stroke:#000000
  classDef chgEmph fill:#388e3c,color:#ffffff,stroke:#000000

  subgraph L3["UI 层（frontend，EDT）"]
    U1["ServerStatusStrip：状态条 + 重试 + 设置入口"]:::chg
    U2["ConnectionSettingsTab：新增 Server 管理区 + CLI 路径"]:::chg
    U3["Notification：CLI 缺失引导 / 凭据缺失提示"]:::chg
  end

  subgraph L2["连接层（既有，仅改地址来源）"]
    C1["BackendChatRepositoryModel：updateServerConfig 唯一入口"]:::std
    C2["OpenCodeRestClient：baseUrl/username/password 构造注入"]:::std
    C3["OpenCodeEventClient：baseUrl/username/password 构造注入"]:::std
  end

  subgraph L1["Server 运行时层（本方案新增，project service）"]
    S1["OpenCodeServerManager：状态机 + 编排 + 自愈（唯一 owner）"]:::chgEmph
    S2["OpenCodeServerDiscovery：探测与复用判定"]:::chg
    S3["OpenCodeServerLauncher：拉起/输出采集/终止"]:::chg
    S4["OpenCodeServerRegistry：自有实例登记 + 引用计数 + 跨进程互斥"]:::chg
    S5["OpenCodeServerEndpointResolver：地址来源优先级"]:::chg
    S6["OpenCodePortAllocator：端口选择（4096 → 备用端口）"]:::chg
    S7["OpenCodeServerOutputBuffer：有界输出缓冲"]:::chg
    S8["OpenCodeServerCliLocator：CLI 路径解析与探测"]:::chg
  end

  U1 --> S1
  U2 --> S1
  U3 --> S1
  S1 --> S2
  S1 --> S3
  S1 --> S4
  S1 --> S5
  S1 --> S6
  S1 --> S8
  S3 --> S7
  S1 -->|"端点就绪后下发"| C1
  C1 --> C2
  C1 --> C3
```

要点：
- `S1` 是**唯一**知道「Server 从哪来、是否由我拉起、引用计数何时归零、何时重启」的地方；连接层与 UI 都只读它的状态流，不自己做进程判断。
- `S3` 是**唯一**持有 `ProcessHandler` 的地方；`S1` 通过它拿「是否存活」「退出码」「输出尾巴」。
- `S4` 是**唯一**读写共享注册表的地方（跨进程文件锁 + 引用计数），`S1` 与其交互完成 acquire/release。
- `S1 → C1` 只在**端点变化**时下发一次（含端口变化），沿用既有 `updateServerConfig`（`BackendChatRepositoryModel.kt:665-683`）——不新增连接机制。

### 3.2 Server 生命周期状态机

```mermaid
flowchart LR
  classDef std fill:#e3f2fd,color:#000000,stroke:#000000
  classDef emph fill:#1976d2,color:#ffffff,stroke:#000000
  classDef chg fill:#c8e6c9,color:#000000,stroke:#000000
  classDef chgEmph fill:#388e3c,color:#ffffff,stroke:#000000
  classDef err fill:#ffcdd2,color:#b71c1c,stroke:#b71c1c

  IDLE["IDLE 未初始化"]:::std
  DISCOVERING["DISCOVERING 探测候选端点"]:::chg
  REUSING["REUSING 复用已存在实例"]:::chg
  NEEDSCRED["NEEDS_CREDENTIALS 需要凭据/未受信"]:::chg
  STARTING["STARTING 拉起子进程并等待就绪"]:::chgEmph
  READY["READY 端点可用"]:::chgEmph
  FAILED["FAILED 失败（带原因分类）"]:::err
  STOPPING["STOPPING 释放引用/优雅终止"]:::std
  STOPPED["STOPPED 已终止（终态）"]:::std

  IDLE -->|"S1 初始化（插件启动）"| DISCOVERING
  DISCOVERING -->|"命中可用且判为自有/可信"| REUSING
  DISCOVERING -->|"命中他人实例，缺凭据（401/403）"| NEEDSCRED
  DISCOVERING -->|"无可用端点且允许自动启动"| STARTING
  DISCOVERING -->|"无可用端点且禁止自动启动"| FAILED
  NEEDSCRED -->|"用户填入凭据并验证通过"| REUSING
  NEEDSCRED -->|"用户取消/改地址"| DISCOVERING
  NEEDSCRED -->|"用户选择改用插件自启实例"| STARTING
  STARTING -->|"就绪探测通过"| READY
  STARTING -->|"就绪探测超时/失败"| FAILED
  REUSING -->|"探测转为不可用"| DISCOVERING
  READY -->|"自有进程退出（自愈额度未耗尽）"| STARTING
  READY -->|"自有进程退出（额度耗尽）/ 健康连续失败"| FAILED
  READY -->|"引用计数归零 / 用户停止"| STOPPING
  FAILED -->|"用户点重试"| STARTING
  FAILED -->|"用户切手动地址后重探"| DISCOVERING
  FAILED -->|"用户停止"| STOPPING
  STOPPING --> STOPPED
```

状态语义与约束：

| 状态 | 语义 | 允许的操作 |
|---|---|---|
| `IDLE` | 尚未决定端点（`S1` 尚未初始化） | `ensureStarted()` 触发 `DISCOVERING`（**插件启动即触发**，不等 Tool Window） |
| `DISCOVERING` | 按 §3.3 的候选顺序逐个探测；只读操作，无副作用 | 探测成功 → `REUSING`/`STARTING`；命中他人实例缺凭据 → `NEEDS_CREDENTIALS`；全部失败 → 依「自动启动开关」分流 |
| `REUSING` | 端点已可用且**非本插件拉起**（或本插件其他窗口拉起） | 只做周期健康探测；**任何情况下不终止他人进程**；自有条目执行引用计数 acquire |
| `NEEDS_CREDENTIALS` | 探测命中**他人启动**的实例，但当前凭据（用户名/密码）不足以通过认证（401/403），需用户提供对应密钥后接入 | 用户填入凭据并验证通过 → `REUSING`；取消/改地址 → `DISCOVERING`；选择自启 → `STARTING`。**不**自动重试、**不**终止该进程 |
| `STARTING` | 已拉起子进程（或正准备拉起），正在轮询就绪 | 就绪 → `READY`；失败 → `FAILED`（带分类） |
| `READY` | 端点可用，已下发给连接层 | 健康探测、自愈重启、引用计数 release、终止 |
| `FAILED` | 终止态的用户可见失败；保留原因分类与输出尾巴 | 重试（→`STARTING`）、改地址后重探（→`DISCOVERING`）、停止 |
| `STOPPING` | 正在释放自有实例引用；仅在引用计数归零时真正终止进程 | 不可中断，直到进程退出或强杀完成 |
| `STOPPED` | 终态（项目已关闭或用户显式停止） | 仅 `ensureStarted()` 可重新激活 |

> 与 TSD-30 §5.4 生命周期契约的继承：`S1` 作为 project service（`@Service(Level.PROJECT)` + `Disposable`），其 `dispose()` 由 Project 关闭触发；dispose 的行为是**释放一个引用**（见 §3.7），不等价于必然终止进程。UI 侧不得自建 Timer/Scope 挂状态条，订阅必须挂 ViewModel/`Content` 的 `Disposable`（同《TSD-30-会话面板整体优化方案》§4.2 C-生命周期）。

### 3.3 发现与复用策略

**探测时机**：`S1` 作为 project service 在**插件启动（项目打开）时即执行一次 `DISCOVERING`**，不等待用户打开 Tool Window（决策要求「探测发生在插件启动时」）。Tool Window 打开只订阅状态流，不触发新的探测轮次（除非处于 `FAILED`/`NEEDS_CREDENTIALS` 需人工介入）。

**候选地址顺序（探测顺序）**

1. 设置项 `OpenCodeSettingsState.serverUrl`（`OpenCodeSettingsState.kt:20`）——用户**显式**配置为非默认值时优先，**永远第一个探测**。
2. 默认地址：环境变量 `OPENCODE_SERVER_URL`（设置留空/为默认值时生效，本方案新增）若存在则用它，否则 `http://127.0.0.1:4096`（**默认端口 4096**，`BackendChatRepositoryModel.kt:47` 的既有默认）。
3. **备用端口候选**：`http://127.0.0.1:4097` 起，最多 N 个（建议 N=8），用于承接「4096 被占用 / 上次退避到备用端口」的场景。

> 固定相对顺序：**默认端口 4096 → 备用端口**（用户决策）。

**「别人的 server」vs「自己的 server」如何区分**

判据是**进程所有权 + 归属标记**，而不是「地址是否相同」。判定顺序：

1. **注册表命中（最强）**：`OpenCodeServerRegistry` 记录了本插件（跨 IDE 实例共享）拉起过的实例 `{port, pid, startedAt, pluginVersion, refCount, references[]}`。探测该端点的 `GET /api/info` 成功且 `info.pid == 登记 pid` → 判定为「自有实例」（可能是另一个 IDE 窗口拉起的，可复用、可接管终止、执行 acquire）。
2. **端口上存在可用实例但注册表无记录**：判定为「外部/他人实例」（用户手动、桌面端或他人工具拉起）。
   - 若能用当前凭据聊上（`GET /api/info` 返回 2xx）→ `REUSING`（**只复用，绝不终止**）。
   - 若返回 401/403（拿不到凭据）→ 转 `NEEDS_CREDENTIALS`，**提示用户输入该实例的密钥**（§4.5），验证通过后接入。
3. **`/api/info` 结构与预期不符**（端口上跑的是别的服务）：不算「可用实例」，继续下一个候选；若该端口是我们自己要用的端口，则视为「端口占用」失败（见 §3.5 失败分类）。
4. **仅当进程由本插件拉起，才允许写入注册表**；注册表条目在**引用计数归零且进程确认退出后**清理（§3.7）。

> 「自有实例」的强判据是 **pid 比对 + 注册表归属标记**，而不是「我们用固定密码」；密码只用于**能不能聊上**，不用于**判断是谁的**。这样即便用户把密码改成同一个，也不会误判所有权而误杀进程。

**多 IDE 实例并发：如何避免重复启动与端口争用**

- 注册表文件带**跨进程文件锁**（`java.nio.channels.FileLock`，见 §12 A12：可用性需运行时验证）：`DISCOVERING → STARTING` 前先在锁内「先探测注册表命中，再决定是否新建」。
- 端口分配用「探测到空闲 → 立即启动 → 启动后就绪探测里校验 `info.pid` 等于本进程子进程 pid」的**双重校验**，避免 TOCTOU（分配时端口空闲，启动时已被他人占用）导致的「看似起来了其实连到别人」。
- 若 `info.pid` 不匹配本进程子进程 pid：视为端口争用失败，**丢弃该子进程**、换备用端口重试（重试上限见 §3.7）。
- 注册表确实是共享资源；不允许无锁写入（写入为「读-改-写」，全程持锁）。

### 3.4 端口与地址来源优先级

| 优先级 | 来源 | 用途 | 备注 |
|---|---|---|---|
| 1 | 设置项 `OpenCodeSettingsState.serverUrl`（显式非默认值） | 探测目标（**不**作为新实例的绑定端口来源，除非用户显式给了非默认端口） | 用户显式配置优先 |
| 2 | 默认端口 `http://127.0.0.1:4096`（或环境变量 `OPENCODE_SERVER_URL`，见 §12 A15） | 探测目标 + **新实例首选绑定端口** | 默认与插件启动**都用 4096**（用户决策） |
| 3 | 备用端口 `http://127.0.0.1:4097` 起（最多 N 个） | 探测目标 + 4096 冲突时新实例的绑定端口 | 用户决策：冲突时启用备用端口 |
| 4 | 设置项显式指定的非默认端口（如 `:4097`） | 新实例绑定端口 | 绑定失败即报「端口占用」，**不静默换端口** |

两条补充规则（避免歧义）：
- **默认端口与插件启动端口统一为 4096**：不再「随机端口优先」。新建自有实例时先尝试绑定 4096；`4096` 被占用（或争用失败）则依次尝试备用端口 4097、4098…（上限 N）。这与「探测顺序 4096 → 备用端口」一致。
- 用户若在设置里**显式指定非默认端口**（例如 `http://127.0.0.1:4097`）：启动自有实例时优先绑定该端口；绑定失败视为「端口占用」失败并提示改端口（不静默换端口，避免用户配置与实际不一致）。

### 3.5 就绪探测与失败分类

**探测契约**

- 端点：`GET /api/info`（`OpenCodeRestClient.kt:414`，服务信息 version/pid/urls/paths）。
- 认证：HTTP Basic（复用 `OpenCodeAuth.basicHeader`，`OpenCodeAuth.kt:15-19`）；密码为空则不带头（与既有语义一致）。
- 成功判据：`2xx` 且响应体可解析为 JSON 对象，且**不是 SPA fallback**。注意既有踩坑（记忆 `mem_d5e71f6f`）：v1 路径会 fallback 返回 SPA 的 `index.html` 并 `200`，因此必须具备「响应体不是 HTML」这一判据，否则会把「端口上跑着别的服务」误判为就绪。
- **探测实现**：使用 JDK `HttpURLConnection` + `Proxy.NO_PROXY`（`URI(url).toURL().openConnection(Proxy.NO_PROXY)`，见 §12 A13），**显式关闭代理**，避免回环探测被系统/IDE 代理改写；与同模块 `OpenCodeRestClient` 同栈，**不**引入新客户端。
- **未就绪判据**：`opencode` v2.0.18 的 OpenAPI 契约中 `/api/info` **只有 200/400/401，没有 503**（§12 A14）。因此「未就绪」判据为：**连接拒绝 / 连接超时 / 响应体非 JSON / 状态码非 2xx**，**不得**依赖 503。
- 轮询节奏：首次立即探测；随后指数退避（建议 `250ms → 500ms → 1s → 2s`，上限 2s）；总超时建议 20s（参数集中一处，可配置）。
- 每次探测用短超时（建议连接 500ms~1s）；不复用 `OpenCodeRestClient` 的 10s 连接/30s 读取超时（`OpenCodeRestClient.kt:891,894`）——就绪探测要快失败。

**失败分类（决定 UI 文案与后续动作）**

| 分类 | 触发信号 | 后续动作 | UI 文案方向 |
|---|---|---|---|
| `CLI_NOT_FOUND` | 启动进程时 `Executable not found` / 退出码 127 / PATH 无 `opencode` 且未配置 cliPath | 不重试自动启动（除用户改了路径），转 `FAILED`；同时发**引导安装通知** | 提示安装或手动指定 CLI 路径（§5） |
| `PORT_IN_USE` | 子进程因绑定失败退出；或探测命中 `info.pid` ≠ 子进程 pid | 换**备用端口**重试（上限内） | 提示端口被占用/自动换端口结果 |
| `AUTH_FAILED` | 探测返回 `401/403`。若为**他人实例** → 转 `NEEDS_CREDENTIALS`；若为自有实例 → 保留 `FAILED` | 他人实例：等待用户输入密钥；自有实例：**停止重试**（与 `OpenCodeEventClient` 口径一致，`OpenCodeEventClient.kt:117-124`） | 提示去设置页填写/核对凭据 |
| `READY_TIMEOUT` | 子进程存活但总超时内未就绪 | 带输出尾巴转 `FAILED` | 提示查看输出/重试 |
| `PROCESS_EXITED` | 子进程在就绪前退出 | 带退出码与输出尾巴转 `FAILED` | 提示退出码与输出摘要 |
| `UNREACHABLE` | 连接被拒/超时且无进程可依据 | 视场景（探测阶段/自愈阶段）分流 | 提示 Server 不可达 |

> 分类必须是**可观测的枚举值**（进日志与 RPC），不是自由文本；UI 文案由分类 + 补充信息拼装，便于国际化与排查。

### 3.6 进程启动与输出采集

- 命令：`opencode serve --port <port>`；工作目录设为当前 Project 的 `basePath`（与 `BackendChatRepositoryModel.workspaceDirectory` 的来源一致），保证 server 默认会话归属与工作区一致（对齐《TSD-07-主界面布局设计》§6 缺口 2 的意图）。
- **密码只经环境变量传递**（`OPENCODE_SERVER_PASSWORD`，与 `OpenCodeCredentials.kt:36` 同名，且经实证为 opencode 识别的官方环境变量，见 §12 A11/A15），**绝不进命令行参数**——macOS/Linux 下 `ps` 可见 argv，写进参数等于把密码公开（对齐《TSD-30-会话面板整体优化方案》§1.3「凭据只走 PasswordSafe，不入日志」以及本方案的日志边界）。
- 进程对象：`GeneralCommandLine` + **`KillableProcessHandler`**（`KillableProcessHandler` 继承 `OSProcessHandler`，额外提供公开的 `killProcess()` 与软/强杀开关，见 §12 A3/A4）；并调用 `setShouldDestroyProcessRecursively(true)` 使 `destroyProcess()` 带走进程树。**不**直接调用 `killProcessTree()`（`OSProcessHandler` 上该方法为 `protected`，外部不可调用）。
- 环境变量注入：`GeneralCommandLine.withEnvironment(name, value)` 注入 `OPENCODE_SERVER_PASSWORD`；父环境继承用默认的 `ParentEnvironmentType.CONSOLE`（见 §12 A11）。
- CLI 路径：由 `OpenCodeServerCliLocator` 解析——优先设置项 `cliPath`，否则从 PATH 查找 `opencode`；找不到即 `CLI_NOT_FOUND`（引导见 §5.3）。
- 输出采集：注册 `ProcessListener`（`onTextAvailable(ProcessEvent, Key)`，`Key` 即 `ProcessOutputType`）区分 stdout/stderr；写入 `OpenCodeServerOutputBuffer`（有界环形缓冲，建议 ≤200 行 / 每行截断，且**逐行做密码脱敏**）。
- **日志量控制**（对齐《HelloBike Java 工程规约》§日志「单条不超过 20KB」与《TSD-30-会话面板整体优化方案》§4.2 C-日志）：
  - 正常启动只记 `INFO`：`Starting server: port=<n>`、`Server ready: pid=<n> url=<url>`、`Server exited: code=<n>`。
  - 进程 stdout/stderr **默认不逐行进 `idea.log`**，只在 `LOG.debug` 输出，且受 Debug Log Settings category 控制；`FAILED` 时才把输出尾巴（脱敏后）附到状态与对话框。
  - 输出缓冲是**内存**的，不落盘（除非用户显式导出日志走平台 `Help | Collect Logs and Diagnostic Data`）。

### 3.7 优雅终止与归属（引用计数模型）

**归属事实**：`S1` 必须持有「是否由我拉起」的**布尔事实**（而非根据端口/地址推断）。`OpenCodeServerLauncher` 返回一个句柄对象，`S1` 只在句柄非空时允许终止。

**引用计数**：自有实例由本机所有 IDE 窗口（多工作区/多窗口）**共享**，生命周期由共享注册表上的引用计数决定。

- 每个引用者 = 一个 IDE 进程中的一次 project 使用，以 `referenceId = "<ideProcessId>:<projectHash>"` 标识。
- **acquire**：`S1` 命中注册表中的自有实例（pid 匹配）时 `acquire()`：`refCount + 1`，并把 `referenceId` 记入 `references[]`（全程持文件锁）。
- **release**：Project 关闭 / 插件卸载（`S1.dispose()`）时 `release()`：从 `references[]` 移除本 `referenceId`，`refCount - 1`。
- **「最后一个引用者」判定**：`release()` 在锁内完成后 `refCount == 0` **且** `references[]` 为空 → 该次 release 的调用方即最后一个引用者，由它执行优雅终止（`destroyProcess()` → 宽限 → `KillableProcessHandler.killProcess()`；因 `setShouldDestroyProcessRecursively(true)`，会带走进程树），随后删除注册表条目。
- **单个引用者关闭不终止**：`refCount > 0` 时 release 只递减，不动进程。

| 场景 | 动作 | 理由 |
|---|---|---|
| 单个 Project 关闭 / 某窗口卸载（`S1.dispose()`，仍有其他引用者） | 仅 `release()`（refCount -1），**不终止进程** | 用户决策：多工作区/多窗口共享同一 server |
| 最后一个引用者关闭（refCount 归零） | `destroyProcess()`（SIGTERM 语义）→ 等待宽限（建议 3s）→ 仍存活则 `killProcess()`（进程树强杀，经 `setShouldDestroyProcessRecursively(true)`）→ 删注册表条目 | PRD §五「Windows 进程残留」；`destroyProcess()` 在 Windows 上未必能带走子进程树 |
| 用户显式「停止 Server」 | 等价于「强制归零」：确认后清空该实例引用并终止；对 `REUSING`（他人实例）该动作**禁用** | 不能杀用户自己起的 server |
| `REUSING`（他人实例） | **永不终止**；最多断开连接（`OpenCodeEventClient.stop()`，`OpenCodeEventClient.kt:72-84`） | 归属原则 |
| 启动中途失败回滚 | 若子进程已创建则同样执行优雅终止，避免半死进程残留 | 同上 |
| 端口争用丢弃 | 同上（见 §3.3） | 同上 |

**崩溃残留清理**：注册表条目记录 `pid` 与每个引用者的 `heartbeatAt`。

- `DISCOVERING` 阶段（持锁）执行清理：`pid` 已不存在的条目整体删除（陈旧条目）；对存活实例，删除 `heartbeatAt` 超过阈值（建议 ≥ 5 分钟）的引用者条目（疑似崩溃未 release），并把 `refCount` 重算为 `references[]` 长度。
- 若清理后 `refCount == 0` 但进程仍存活：视为崩溃残留，由当前探测方接管为自有实例并 acquire（或按策略终止，见实现取舍）。
- 用户显式「重置 Server 管理」按钮可强制清空注册表（兜底排障入口）。

> 平台 API 实证：`destroyProcess()` 为 `ProcessHandler` 的公开方法；软/强杀由 `KillableProcessHandler.killProcess()` / `canKillProcess()` 提供；`killProcessTree` 为 `OSProcessHandler` 的 `protected` 方法（不可外部调用）。详见 §12 A3/A4（其中 `destroyProcess`/`killProcess` 的**语义差异**以签名为据，Windows 行为需实测）。

### 3.8 自愈：进程退出后谁来拉起

| 事件 | 责任方 | 行为 |
|---|---|---|
| 事件流断线（server 存活） | `OpenCodeEventClient`（既有） | 指数退避重连 + 重连成功后 REST 对账；**不**拉起进程 |
| 自有进程意外退出 | `OpenCodeServerManager`（新增） | `ProcessListener.processTerminated` → 若状态为 `READY` 且仍有引用者：按退避重启，**上限 3 次 / 10 分钟窗口**；超出 → `FAILED`（原因 `PROCESS_EXITED`），等用户重试。若引用计数已归零则不重启 |
| 重启后端口可能变化 | `OpenCodeServerManager` | 端点变化 → 再次 `updateServerConfig` → `BackendChatRepositoryModel` 重建事件流（`:665-683` 已有此行为） |
| 健康连续失败（进程存活但端点不可用） | `OpenCodeServerManager` | 连续 N 次（建议 3 次，间隔 10s）探测失败 → 视为死亡，走同一自愈路径 |
| 他人实例（`REUSING`）失联 | `OpenCodeServerManager` | 不重启（不是我们的进程）；回 `DISCOVERING` 重新探测，全失败则依自动启动开关分流 |

**边界划清**：进程拉起只由 `S1` 负责；`OpenCodeEventClient` / `BackendChatRepositoryModel` **不**负责拉起进程，只负责「连上/重连/对账」。这样避免「事件流重连循环 → 顺手起进程」这类隐蔽的重复启动。

---

## 4. 连接与凭据

### 4.1 地址与凭据来源优先级

| 场景 | `baseUrl` 来源 | `username` 来源 | `password` 来源 |
|---|---|---|---|
| 复用他人实例 | 探测命中的候选地址 | 设置项 `OpenCodeSettingsState.username`（默认 `opencode`），或用户在 `NEEDS_CREDENTIALS` 交互中提供的用户名 | `OpenCodeCredentials.resolvePassword(设置页密码)`：显式值 → `OPENCODE_SERVER_PASSWORD` → `~/.config/opencode/service.json`（`OpenCodeCredentials.kt:16-24`）；缺凭据时由 §4.5 交互补充 |
| 复用自有实例（含另一 IDE 窗口拉起） | 注册表登记的 `url` | 插件自有用户名（建议固定 `opencode`） | **插件生成并仅存 `PasswordSafe`**（§4.2），优先级高于环境变量与 `service.json` |
| 新建自有实例 | 由分配端口构造 `http://127.0.0.1:<port>`（首选 4096） | 同上 | 同上（先定密码，再以该密码注入子进程环境变量并登记） |

> 关键点：**自有实例的密码不能沿用 `service.json` / 环境变量链**——那条链是给「别人起的实例」用的；自有实例必须用「本插件自己定的密码」，否则一旦用户本机存在 `OPENCODE_SERVER_PASSWORD`，多个实例会互相串密码。自有实例的密码取值优先级为最高，且不写入任何非凭据存储的位置。

### 4.2 凭据存储

- 密码**只**进 `PasswordSafe`（沿用既有机制，`OpenCodePasswordStore.kt:20-39` 的 `CredentialAttributes` 形态）；自有实例密码建议使用**独立 account 名**（如 `server-instance`）与用户手填密码（account `opencode`）分开，避免互相覆盖。
- 自有实例密码需在**插件运行期可复现**（重启 IDE 后仍能复用注册表里的实例）：实现上由 `PasswordSafe` 持久化，注册表只存 `port/pid/startedAt/refCount/references`，**不存密码**。
- 密码可以随机生成（建议平台安全随机 + 足够长度），不需要用户知晓；UI 也不展示（最多展示「由插件管理」）。
- 他人实例的密钥（用户手输）同样只进 `PasswordSafe`（沿用既有 account `opencode`），不进设置文件、不进日志。

### 4.3 认证失败（401）处理路径

1. 探测/健康检查收到 `401/403`：
   - 若目标为**他人实例** → 转 `NEEDS_CREDENTIALS`，走 §4.5 交互（**不**做指数退避空转）。
   - 若目标为**自有实例** → 分类 `AUTH_FAILED` → **立即停止重试**（与 `OpenCodeEventClient.kt:117-124` 口径一致），转 `FAILED`。
2. `S1` 把分类透出 → 状态条提示「认证失败」+「打开设置」按钮（直达 `OpenCodeSettingsConfigurable` 的连接页）。
3. 用户在设置页改完并点「OK」→ 既有路径下发（`ConnectionSettingsTab.kt:74-92`）→ `S1` 收到端点变更 → 重新 `DISCOVERING`（**不**自动重启自有进程，因为自有进程不受 401 影响；401 通常意味着复用了别人的实例）。
4. 若明确为他人实例且用户拒绝提供密钥 → 明确提示「该端口上的 Server 不是本插件启动的，可填写凭据接入，或改用手动地址、或改用插件自启实例」。

### 4.4 日志边界（硬约束）

- 密码/token **一律不落日志**，也不出现在 `FAILED` 的输出尾巴里（输出缓冲写入时逐行脱敏）。
- 允许记录的：端口、pid、`baseUrl`（不含凭据）、`info.version`、失败分类枚举、退出码、引用计数。
- `baseUrl` 若含用户信息（`http://user:pass@host`，理论上用户可能误填）→ 记录前剥离 userinfo。

### 4.5 他人实例的凭据交互（`NEEDS_CREDENTIALS`）

- **触发**：`DISCOVERING` 命中某端点的 `GET /api/info` 返回 `401/403`（`NEEDS_CREDENTIALS`），说明该端口有 Server 但不是我们的、且当前凭据不可用。
- **交互**：状态条给出「该 Server 不是本插件启动，需要密钥才能接入」+ 就地输入框（用户名默认 `opencode` + 密码）+「接入」/「改用插件自启实例」/「改用手动地址」三个动作。
- **验证**：提交后用该凭据重试 `GET /api/info`，2xx → `REUSING`；仍 401 → 保持 `NEEDS_CREDENTIALS` 并提示凭据不符。
- **存储**：仅存 `PasswordSafe`（account `opencode`），复用既有 `OpenCodePasswordStore`。
- **不终止**：无论用户是否接入，该进程的生命周期一律不由本插件管理。

---

## 5. UI 反馈

### 5.1 Tool Window 内状态呈现

位置按《TSD-07-主界面布局设计》§1 的三段式布局：在顶部 `TopBar` 与中部 `ChatList` 之间插入**一条可折叠的状态条** `ServerStatusStrip`（仅在有非 `READY` 状态时显示，`READY` 时整条隐藏，避免长期占用纵向空间）。

```mermaid
flowchart LR
  classDef std fill:#e3f2fd,color:#000000,stroke:#000000
  classDef chg fill:#c8e6c9,color:#000000,stroke:#000000
  classDef err fill:#ffcdd2,color:#b71c1c,stroke:#b71c1c
  classDef okn fill:#c8e6c9,color:#1b5e20,stroke:#1b5e20

  D["DISCOVERING / STARTING"]:::chg
  R["READY"]:::okn
  F["FAILED"]:::err
  N["NEEDS_CREDENTIALS"]:::chg
  X["REUSING（他人实例）"]:::std

  D -->|"显示：正在启动 Server…（含端口）"| D1["隐藏重试，允许取消"]:::chg
  X -->|"整条隐藏"| X1["不暴露停止按钮"]:::std
  N -->|"显示：需要密钥才能接入"| N1["就地输入凭据 + 改用自启/手动地址"]:::chg
  R -->|"整条隐藏"| R1["无 UI 占用"]:::okn
  F -->|"显示：失败分类文案 + 输出摘要（可展开）"| F1["重试按钮 + 打开设置按钮"]:::err
```

| 状态 | 状态条内容 | 可用操作 |
|---|---|---|
| `DISCOVERING`/`STARTING` | 「正在启动 OpenCode Server（端口 4096）…」+ 进度指示 | 取消（→ `STOPPING`）、无重试 |
| `REUSING`（他人实例） | 默认隐藏 | 不暴露停止按钮 |
| `NEEDS_CREDENTIALS` | 「该 Server 不是本插件启动，需要密钥才能接入」+ 凭据输入 | **接入**、**改用插件自启实例**、**改用手动地址** |
| `READY` | 隐藏 | — |
| `FAILED` | 分类文案（§3.5）+ 可展开输出尾巴（脱敏） | **重试**、**打开设置**（连接页）、**手动配置 Server 地址**入口 |
| `STOPPED` | 隐藏（项目即将关闭） | — |

`ServerStatusStrip` 订阅 `S1` 的状态流（经 RPC `Flow` 透传），UI 更新一律经 EDT；其生命周期挂 `Content.setDisposer` 或 ViewModel（继承《TSD-30-会话面板整体优化方案》§5.4 的订阅纪律，不复用 project 级 scope）。

### 5.2 与现有 Settings 的关系

- **沿用**既有连接页三项（地址/用户名/密码）的语义与存储，不迁移（`ConnectionSettingsTab.kt:38-58,74-92`）。
- **新增**一个「Server 管理」分组（同一 Tab 内，置于现有表单之后）：
  - 「自动启动 Server」开关（**默认开**，见 §13）；
  - 「复用已有 Server（含非本插件启动的）」开关（默认开；关闭则只复用自有实例，其余直接 `FAILED` 并引导手填）；
  - 「opencode CLI 路径」可选覆盖项（默认空 = 从 PATH 解析；`CLI_NOT_FOUND` 时此处即为兜底入口）；
  - 「停止本插件启动的 Server」按钮（仅当当前为自有实例时可点；**点击即强制归零引用并终止**）；
  - 「重置 Server 管理（清空注册表）」按钮（排障兜底，见 §3.7）。
- 现有「测试连接」按钮（`ConnectionSettingsTab.kt:108-129`）语义不变；可选增强为复用 `OpenCodeServerDiscovery` 的探测分类，使失败原因更精确（属可选，见 T8）。

### 5.3 Notification（CLI 引导等场景）

- **引入通知**（用户决策要求 CLI 缺失时「提醒并引导安装」），注册在 **frontend 模块**（`opencode-idea-panel.opencode-frontend.xml`，`extensions defaultExtensionNs="com.intellij"`）：

  ```xml
  <notificationGroup id="OpenCode.Server"
                     displayType="BALLOON"
                     bundle="messages.OpencodeFrontendBundle"
                     key="notification.group.server"/>
  ```

  （`notificationGroup` 扩展点写法取自 IDE 内置插件实证，见 §12 A10。）
- **触发场景**（均遵守「不弹凭据、不给可执行外链、外链仅指向官方文档/官网」）：
  1. `CLI_NOT_FOUND`：WARNING 通知，正文给「未找到 opencode CLI」，action 为**打开官方安装文档/官网**，并提示可在设置页填写 CLI 路径。
  2. 后台自愈重启失败（`FAILED` 且 Tool Window 不可见）：WARNING 通知，action 为「打开面板/设置」。
  3. （可选）`NEEDS_CREDENTIALS` 且用户未打开 Tool Window：一次性 INFORMATION 提示，**不**在通知内联凭据输入，仅引导打开面板。

---

## 6. 与既有模块的边界

### 6.1 新增包与类（`opencode-backend`）

包：`com.ayongw.idea.opencode.backend.server`

| 类 | 类型 | 职责 | 可单测性 |
|---|---|---|---|
| `OpenCodeServerState` | `enum` | 状态枚举（§3.2，含 `NEEDS_CREDENTIALS`）+ 失败分类 `OpenCodeServerFailure` | 纯数据 |
| `OpenCodeServerEndpoint` | `data class` | `{baseUrl, username, password, port, source, owned}` | 纯数据 |
| `OpenCodeServerEndpointResolver` | `object`/`class` | 地址来源优先级（§3.4） | 纯逻辑（注入 env / 设置值） |
| `OpenCodePortAllocator` | `class` | 端口选择（4096 → 备用端口），可注入假实现 | 纯逻辑（可注入） |
| `OpenCodeServerCliLocator` | `class` | CLI 路径解析（cliPath → PATH）与存在性探测 | 纯逻辑（注入 PATH / 假文件系统） |
| `OpenCodeServerDiscovery` | `class` | 探测 + 就绪判定 + 失败分类（§3.3/§3.5） | 纯逻辑（HTTP 层用本地 `HttpServer` 桩横切） |
| `OpenCodeServerRegistry` | `class` | 自有实例登记 + **引用计数** + 跨进程互斥 + 陈旧/崩溃残留清理（§3.3/§3.7） | 纯逻辑（注入文件路径） |
| `OpenCodeServerLauncher` | `class` | `GeneralCommandLine` + `KillableProcessHandler` + 终止 + 输出采集（§3.6/§3.7） | 需平台运行环境，走 `ITest`/短命进程桩 |
| `OpenCodeServerOutputBuffer` | `class` | 有界环形缓冲 + 逐行脱敏 | 纯逻辑 |
| `OpenCodeServerManager` | `@Service(Level.PROJECT)` + `Disposable` | 状态机 + 编排 + 自愈 + 状态流（§3.2/§3.8），**唯一 owner** | 编排逻辑可注入依赖后单测 |

注册：`@Service` 注解式（与 `BackendChatRepositoryModel.kt:43` 同形态）。

**模块依赖（v1.2 实证修正，见 §12 A8）**：`opencode-backend` **无需新增任何模块依赖**——
- 进程管理类 `com.intellij.execution.configurations.GeneralCommandLine`、`com.intellij.execution.process.OSProcessHandler` / `KillableProcessHandler` / `ProcessListener` / `ProcessOutputType` 实际都在 **`util.jar`**（模块 `intellij.platform.util`，公开可见性），随 `intellij.platform.backend` 传递可得；
- **不得**声明 `intellij.platform.execution`：该模块为 **internal 可见性**（注册在 `com.intellij` 插件、namespace `jetbrains`），插件 content module 声明它会被平台拒绝——测试沙箱启动即报 `... depends on module 'intellij.platform.execution' which is registered in 'com.intellij' plugin with internal visibility`，插件整体不被加载（v1.1 的「需新增」结论已作废）；
- 通知相关类型（`com.intellij.notification.*`）属 `intellij.platform.ide.core`，仍建议**在 frontend 侧使用**（frontend 已声明 `intellij.platform.frontend`），引入前先以 Gradle 解析确认可得性。

### 6.2 前端新增

| 文件 | 职责 |
|---|---|
| `chatApp/ui/ServerStatusStrip.kt` | 状态条组件（§5.1，含 `NEEDS_CREDENTIALS` 凭据输入） |
| `chatApp/ui/OpenCodeChatApp.kt`（改） | 装配状态条到 `TopBar` 与 `ChatList` 之间；订阅状态流 |
| `settings/ConnectionSettingsTab.kt`（改） | 新增「Server 管理」分组 + CLI 路径 + 凭据交互（§5.2） |
| `settings/OpenCodeSettingsState.kt`（改） | 新增 `autoStartServer` / `reuseExternalServer` / `cliPath` 三个字段（密码字段不动） |
| `messages/OpencodeFrontendBundle.properties`（改） | 新增状态条、设置项与通知文案 |
| `opencode-frontend/src/main/resources/opencode-idea-panel.opencode-frontend.xml`（改） | 注册 `notificationGroup` 扩展点（§5.3） |

### 6.3 对既有连接层的影响（仅「地址来源」变化）

| 文件 | 变化 | 不变 |
|---|---|---|
| `repository/OpenCodeRestClient.kt` | **无改动**（构造仍为 `baseUrl/username/password`，`:30-34`） | 全部端点与解析 |
| `backend/event/OpenCodeEventClient.kt` | **无改动**（构造仍为 `baseUrl/username/password`，`:25-34`） | 重连/退避/401/读超时语义 |
| `OpenCodeCredentials.kt` | 不改解析链；自有实例密码的取值在 `Resolver` 层优先于它 | `service.json` 定位（`:34`） |
| `BackendChatRepositoryModel.kt` | **仅**新增：`updateServerConfig` 的调用方由「设置页」扩展为「设置页 + `S1`」；`dispose()`（`:852-856`）需保证顺序正确（先 `S1` 释放引用/终止自有进程 → 再停事件流） | 消息/事件/对账全部逻辑 |
| `shared/ChatRepositoryRpcApi.kt` + `BackendChatRepositoryRpcApi.kt` | 新增 `getServerStateFlow` / `retryServerStart` / `stopServer`（自有实例）/ `submitServerCredentials`（他人实例凭据） | 既有全部 RPC |

### 6.4 对《TSD-30-会话面板整体优化方案》§5.4 生命周期契约的继承

- `S1` 的 `Disposable` 由 Project 提供（`@Service(Level.PROJECT)`），**不**使用 `Disposer.register(S1, ...)` 自注册到别处。
- `S1.dispose()` = 一次 `registry.release()`（§3.7）：**refCount 未归零则不终止进程**，仅归零时才优雅终止并清理注册表条目；`dispose()` 内需同步等待到「已发出终止信号」或确认进程退出（避免 IDE 退出时进程残留）。
- `S1` 内部的调度（退避重启、健康轮询、心跳）用协程 scope 或平台 `Alarm`/调度器，**必须**在 `dispose()` 内取消；不得用无主 `Timer`。
- 状态条订阅挂 `Content.setDisposer` / ViewModel（同 §5.4 的「面板级 scope」写法），**禁止**再用 `CoroutineScopeHolder` 的 project 级 scope（该点在 TSD-30 §2.1 P0-2 已定性为泄漏）。

---

## 7. 风险与取舍

| # | 风险 | 影响 | 应对（取舍） |
|---|---|---|---|
| R1 | **Windows 进程树残留** | 文件锁定 → 插件升级/卸载失败（PRD §五） | `KillableProcessHandler` + `setShouldDestroyProcessRecursively(true)`：`destroyProcess()` 后宽限再 `killProcess()`；`dispose()` 必须同步等待或至少确认已发出终止；**Windows 行为需实测**（§12 A3/A4） |
| R2 | `opencode` CLI 未安装 / PATH 发现失败 | 自动启动永久失败 | 分类 `CLI_NOT_FOUND` + **通知引导安装（打开文档/官网）** + 设置页「CLI 路径」覆盖项；**不**做自动安装（非目标） |
| R3 | **代理污染回环探测** | 探测被中间件改写或拒绝 | 探测改用 `HttpRequests.request(url).useProxy(false)`（§12 A13），显式关闭代理；仅用 `127.0.0.1` 回环 |
| R4 | **split mode / 远程开发** | 后端可能在**远端主机**运行，此处在远端拉起 `opencode serve` 与用户本机「装没装 CLI」无关，界面提示会误导 | 降级：检测到「后端运行主机非本机」时**默认关闭自动启动**，只做「探测 + 提示」，并引导手填地址。判定 API 已实证（§12 A9：`EelProviderUtil.getEelDescriptor(project)` vs `LocalEelDescriptor.INSTANCE`） |
| R5 | 进程输出含敏感信息 | 日志泄漏 | 输出默认只进 `debug` 且受 category 控制；缓冲逐行脱敏；`FAILED` 展示前再脱敏一次 |
| R6 | 与用户手动启动的 server 冲突 | 双实例抢端口 / 误杀用户进程 | 归属判据用「注册表 + pid 匹配」；他人实例只复用（或经密钥接入）不终止；新实例优先 4096、冲突走备用端口（§3.4） |
| R7 | 复用他人实例但凭据拿不到 | 401 死循环 | 他人实例 `401` → `NEEDS_CREDENTIALS` 交互（不再死循环重试）；自有实例 `AUTH_FAILED` 立即停重试（§4.3） |
| R8 | 自愈重启风暴（server 反复崩溃） | CPU/端口抖动，用户无感知 | 上限 3 次/10 分钟 + 退避；超限转 `FAILED`，不再自动重启 |
| R9 | 引入平台执行框架依赖 | 模块依赖面变化、split mode 下可用性 | **已实证（v1.2）**：进程管理类在公开模块 `intellij.platform.util`（`util.jar`），**无需新增依赖**；`intellij.platform.execution` 为 internal 可见性，**声明它会导致插件不被加载**（§12 A8） |
| R10 | 就绪探测误判（SPA fallback `200`） | 把「别的服务」当 server | 探测必须校验响应体为 JSON 且带期望字段（`pid`/`version`）；**不得**依赖 503（契约无此响应，§12 A14） |
| R11 | 端口分配 TOCTOU | 连到别人的实例 | 启动后用 `info.pid == 子进程 pid` 双重校验，不匹配即换备用端口重试（§3.3） |
| R12 | 引用计数竞态导致误停 | 多窗口下误杀共享 server | acquire/release 全程持跨进程文件锁；「最后一个引用者」以「锁内 refCount==0 且 references 为空」判定；崩溃残留靠心跳超时清理（§3.7） |
| R13 | 跨进程文件锁在 macOS/Windows 语义差异 | 注册表被并发破坏或锁失效 | `FileLock` 可用性标注为**需运行时验证**（§12 A12）；若不可靠，退化为「注册表文件 + 原子写（临时文件 + 原子 rename）+ 不依赖锁」，以幂等写与心跳收敛替代强互斥 |

---

## 8. 独立执行的任务拆分（T1~T13）

> 每个任务都可交给**单独会话或子 agent**；「并行」列为「是」的任务在接口冻结后可与同批任务并行落地。所有任务完成的共同门：`./gradlew compileKotlin test` 绿；涉及打包结构的任务补 `./gradlew buildPlugin`（**不跑 `verifyPlugin`**）。共享前提：先冻结 §3.1 的类名与 `OpenCodeServerEndpoint` / `OpenCodeServerState` 签名（由 T1 产出），后续任务只依赖该签名。

| 任务 | 范围 | 涉及文件 | 前置依赖 | 验证方式 | 可并行 |
|---|---|---|---|---|---|
| **T1** 状态与纯逻辑底座 | 状态枚举（含 `NEEDS_CREDENTIALS`）、失败分类、端点数据类、地址来源优先级解析、端口分配接口（4096 → 备用端口）、输出缓冲（含脱敏）；**不含**任何 IDE/网络调用 | `server/OpenCodeServerState.kt`、`OpenCodeServerEndpoint.kt`、`OpenCodeServerEndpointResolver.kt`、`OpenCodePortAllocator.kt`、`OpenCodeServerOutputBuffer.kt` | 无 | `OpenCodeServerEndpointResolverUnitTest`（优先级矩阵、空值、默认回落）、`OpenCodePortAllocatorUnitTest`（4096 可用/被占/备用顺序）、`OpenCodeServerOutputBufferUnitTest`（有界、截断、脱敏） | 是 |
| **T2** 发现与就绪探测 | 候选顺序探测、Basic 认证、JSON 判据（拒 SPA fallback）、`HttpRequests.useProxy(false)`、轮询退避、失败分类（含「未就绪 = 连接拒绝/超时/非 JSON」，**不依赖 503**） | `server/OpenCodeServerDiscovery.kt` | T1（签名） | `OpenCodeServerDiscoveryUnitTest`：用 `com.sun.net.httpserver.HttpServer` 桩覆盖 200-JSON / 200-HTML / 401 / **503（应归类为 UNREACHABLE 或按实现约定）** / 连接拒绝 / 超时 → 分类断言 | 是（与 T3 并行） |
| **T3** 共享注册表基础 | 注册表文件读写、**跨进程互斥**（`FileLock` 或原子写降级）、陈旧条目（pid 不存在）清理、条目结构（含 `pid/refCount/references/heartbeatAt`） | `server/OpenCodeServerRegistry.kt` | T1（签名） | `OpenCodeServerRegistryUnitTest`：注入临时目录；覆盖并发读写、原子写、陈旧条目清理、pid 不匹配不判为自有 | 是（与 T2 并行） |
| **T4** 进程拉起与终止 | `GeneralCommandLine` + **`KillableProcessHandler`** + `setShouldDestroyProcessRecursively(true)`、密码经环境变量、工作目录、输出监听、优雅终止（`destroyProcess` → 宽限 → `killProcess`）、CLI 路径解析 | `server/OpenCodeServerLauncher.kt`、`server/OpenCodeServerCliLocator.kt` | T1（签名）；§12 平台 API 已核实（A1~A5） | `OpenCodeServerLauncherITest`（`*ITest`，默认排除）：用**短命进程桩**验证「启动→输出采集→终止→无残留」；手工在 Windows 上验证进程树 | 是 |
| **T5** 生命周期编排与自愈 | 状态机（含 `NEEDS_CREDENTIALS` 转移）、编排 T2/T3/T4、退出监听、退避重启与上限、健康轮询、状态流 `StateFlow` | `server/OpenCodeServerManager.kt` | T1–T4 | `OpenCodeServerManagerUnitTest`（注入假 Discovery/Launcher/Registry）：状态转移全覆盖、自愈上限、401 分流（他人→NEEDS_CREDENTIALS / 自有→FAILED）、他人实例不终止 | 否（依赖 T1–T4） |
| **T6** 连接层接线 | `S1` 端点到 `BackendChatRepositoryModel.updateServerConfig`；`dispose()` 顺序（先释放引用 → 再停事件流）；`Project.basePath` 作为工作目录来源 | `BackendChatRepositoryModel.kt`（小改） | T5 | 既有全量单测不回归 + `OpenCodeServerManagerUnitTest` 的「端点变化触发一次下发」断言 | 否 |
| **T7** RPC 与 shared 契约 | `getServerStateFlow` / `retryServerStart` / `stopServer`（自有实例）/ `submitServerCredentials`（他人实例凭据）（含 DTO：状态、失败分类、端口、是否自有、refCount） | `opencode-shared/.../ChatRepositoryRpcApi.kt`、`opencode-shared/.../dtos.kt`、`BackendChatRepositoryRpcApi.kt` | T5（接口冻结即可开工） | 契约单测（DTO 序列化往返）+ 编译（`rpc` 插件对接口变更的校验） | 可与 T6 并行 |
| **T8** 前端状态条与设置项 | `ServerStatusStrip`（含凭据输入）、装配进 `OpenCodeChatApp`、设置页「Server 管理」分组、`notificationGroup` 注册、bundle 文案、（可选）「测试连接」复用 Discovery 分类 | `chatApp/ui/ServerStatusStrip.kt`、`chatApp/OpenCodeChatApp.kt`、`settings/ConnectionSettingsTab.kt`、`settings/OpenCodeSettingsState.kt`、`messages/OpencodeFrontendBundle.properties`、`opencode-idea-panel.opencode-frontend.xml` | T7 | Swing 冒烟（Platform test framework + `dispatchAllInvocationEventsInIdeEventQueue`，断言状态条显隐与按钮可用性）；装机验收见 §9.2 | 否（依赖 T7） |
| **T9** 测试横切基建 | 短命进程桩脚本（模拟 `opencode serve`：解析 `--port`、暴露 `GET /api/info` 返回 `pid`、可注入「慢启动/拒绝/401」形态）、HTTP 桩工具类 | `src/test/resources/server/` + `src/test/kotlin/.../server/*Support.kt` | 无（可先于 T2/T4 落地，供其复用） | 自身跑通「桩可启停、可切形态」的用例 | 是（应最先/与 T1 并行） |
| **T10** 共享注册表与引用计数 | 引用计数 acquire/release、「最后一个引用者」判定（锁内 refCount==0 且 references 为空）、心跳与崩溃残留清理、强制归零（用户停止 / 重置） | `server/OpenCodeServerRegistry.kt`（扩展）、`server/OpenCodeServerManager.kt`（接入） | T3、T5 | `OpenCodeServerRegistryUnitTest`：两引用者场景（关一不停、关最后一才停）、崩溃残留（心跳过期）清理、并发 release 只终止一次 | 否（依赖 T3/T5） |
| **T11** 他人实例的凭据交互 | `NEEDS_CREDENTIALS` 交互链路：状态透出 → 前端凭据输入 → `submitServerCredentials` → 验证 → `REUSING`/保持 | `server/OpenCodeServerManager.kt`、RPC（T7）、`chatApp/ui/ServerStatusStrip.kt` | T5、T7、T8 | `OpenCodeServerManagerUnitTest`（凭据通过/不通过转移 + 不终止他人进程）+ UI 冒烟（凭据输入可用） | 否 |
| **T12** CLI 检测与引导安装 | `CLI_NOT_FOUND` 检测（PATH/cliPath）、**引导通知**（打开文档/官网，外链仅官方）、设置页 CLI 路径生效与复检 | `server/OpenCodeServerCliLocator.kt`（T4 已含解析，此处补引导）、`chatApp/ui/ServerStatusStrip.kt`、`settings/ConnectionSettingsTab.kt`、`OpencodeFrontendBundle.properties` | T4、T8 | `OpenCodeServerCliLocatorUnitTest`（PATH 命中/未命中/覆写）+ 装机验收 §9.2 第 4 条 | 否 |
| **T13** 文档回填（**本方案落地后**执行，非本次） | 回填《TSD-30-会话面板整体优化方案》§9.2 G4 行状态为「已立项/已实现」；`README.md` 依赖表与「关键技术点」更新；PRD §4.1 补「最低 2026.2」「CLI 路径可覆盖」 | 上述既有文档 | T1–T12 完成 | 人工核对文档与实现一致（无需构建） | 否（收尾） |

**并行批次建议**：批 1 = T9 + T1 → 批 2 = T2 + T3 → 批 3 = T4 + T5 → 批 4 = T6 + T7 + T10 → 批 5 = T8 + T11 + T12 → 收尾 T13。

---

## 9. 验收标准

### 9.1 单测清单（遵守项目规范）

约束：JUnit 4 + Platform test framework；类名后缀 `UnitTest`（单测）/ `ITest`（集成）；`./gradlew test` 默认排除 `*ITest`（`build.gradle.kts:152-158`），集成用 `-Pit=true` 纳入。

| 用例类 | 覆盖点（按功能而非按实现） |
|---|---|
| `OpenCodeServerEndpointResolverUnitTest` | 设置项 > 环境变量 > 默认 4096 的优先级；**探测顺序 4096 → 备用端口**；显式端口用于自有实例绑定；值为空/空白/非法 URL 的回落 |
| `OpenCodePortAllocatorUnitTest` | 4096 可用时首选 4096；4096 被占用时依次取备用端口；备用端口耗尽的上报 |
| `OpenCodeServerOutputBufferUnitTest` | 行数上限、单行截断、密码脱敏（含 `Authorization`/`password=` 形态）、并发写入 |
| `OpenCodeServerDiscoveryUnitTest` | 就绪 / SPA-HTML 伪 200 / 401 / 503 / 连接拒绝 / 超时 → 分类映射；**代理已关闭**（`useProxy(false)`）；轮询退避顺序；总超时触发点 |
| `OpenCodeServerRegistryUnitTest` | 并发写入不互相覆盖；陈旧条目（pid 已不存在）被清理；pid 不匹配不判为自有；**引用计数：两引用者关一不停、关最后一才停**；崩溃残留（心跳过期）清理 |
| `OpenCodeServerLauncherITest` | 短命进程桩：启动成功 + 输出采集；启动失败（不存在命令）→ `CLI_NOT_FOUND`；终止后进程不再存活 |
| `OpenCodeServerManagerUnitTest` | 状态机全转移（§3.2 每条边）；`REUSING`/`NEEDS_CREDENTIALS` 下终止被拒绝；他人实例 401 → `NEEDS_CREDENTIALS`、自有实例 401 → `FAILED` 立即停重试；自愈 3 次上限后转 `FAILED`；端点变化触发一次下发 |
| `ServerStateDtoUnitTest`（shared） | 状态/失败分类 DTO 序列化往返与向后兼容新增字段 |

### 9.2 装机验收清单（不跑 `verifyPlugin`）

1. **默认自动启动成功**：清空设置、机器上无任何 server → **项目打开即自动启动**（无需先打开面板），状态转可用；能正常发消息并收到流式回复。
2. **复用桌面端实例**：先用桌面端/手动在 4096 起 server → 打开项目 → 不产生新进程（`ps` 核对），面板直接可用。
3. **他人实例需密钥路径**：在 4097 起一个带密码的他人 server，且本机无该密码 → 插件探测后进入「需要密钥」，输入正确密码可接入（`REUSING`）；输入错误保持提示；取消后可用插件自启实例。
4. **CLI 缺失引导**：临时把 `opencode` 从 PATH 移走 → 出现「未找到 CLI」状态并按 §5.3 给出**引导通知（可打开文档/官网）**；在设置页填 CLI 路径后重试成功。
5. **引用计数（关一个窗口不停、关最后一个才停）**：两个 IDE 窗口共享同一自启 server → 关闭其中一个窗口，`ps` 中 `opencode serve` **仍存活**、另一窗口可用；关闭最后一个窗口后进程**才**终止、无残留。
6. **端口冲突走备用端口**：先占住 4096 → 插件启动自有实例时按策略落到**备用端口**（4097…）并成功就绪。
7. **探测顺序正确**：4096 与备用端口都有可用实例时，**优先复用 4096**；日志/状态能体现「4096 → 备用端口」的探测顺序。
8. **凭据缺失（自有）**：自有实例凭据错（人为破坏）→ 状态条给「认证失败」+「打开设置」；填对后一次操作即恢复，无重连空转。
9. **进程终止 / 不误杀**：最后一个引用者关闭后 `ps` 无 `opencode serve` 残留（重复 5 次无累积）；他人实例场景下关闭项目后该外部进程**仍存活**。
10. **自愈**：`READY` 后 `kill` 掉自有进程 → 状态条短暂出现启动中并恢复可用；连续杀 4 次 → 转 `FAILED` 不再自动重启，点重试可再起。
11. **构建门**：`./gradlew compileKotlin test buildPlugin --no-daemon --no-configuration-cache` 全绿（**不执行** `verifyPlugin`）。

---

## 10. 测试方案

### 10.1 真实短命进程桩

用系统命令模拟 server，避免依赖真实 `opencode` CLI 与网络：

- 形态：一个可执行脚本（`sh`/`bat` 各一份，或统一走 JVM 内 `HttpServer` 的子进程包装），职责为：解析 `--port`；绑定端口；暴露 `GET /api/info` 返回 `{"data":{"pid":<本进程 pid>,"version":"stub"}}`（具体形态按 §3.5 的判据，**注意 `OpenCodeRestClient.kt:553-559` 的 `objectOrData` 兼容「根对象」与「data 包裹」两种**）；支持从环境变量切换「慢启动 / 拒绝连接 / 返回 401 / 返回 503 / 直接退出」五种形态。
- 用途：`OpenCodeServerLauncherITest`（真实进程与终止语义）+ `OpenCodeServerManagerUnitTest`（编排）。
- 边界：桩只覆盖「进程与 HTTP 交互」，不进入消息/事件语义（那属《TSD-06-事件流接入设计》范围）。

### 10.2 HTTP 横切

- 首选 **仓库已引的 `com.squareup.okhttp3:mockwebserver:4.12.0`**（`build.gradle.kts:113`，且《TSD-06-事件流接入设计》§9.2 已在用），保证测试栈一致。
- 备用 **JDK 自带 `com.sun.net.httpserver.HttpServer`**：用于「必须控制原始响应体/状态码/延迟」的场景（例如返回 HTML 的伪 200、返回 503、延迟 3s 触发退避），无需额外依赖。
- 两者都不引入新依赖，满足「不引入新三方依赖」的非目标。

### 10.3 分层与门

| 层级 | 做法 | 门 |
|---|---|---|
| 纯逻辑（主） | Resolver / PortAllocator / OutputBuffer / Discovery / Registry（含引用计数）/ CliLocator / Manager（注入假依赖）→ `UnitTest` | 必过 |
| 进程与 HTTP 半集成 | 短命进程桩 + `ITest`（默认排除，发布前或改动该链路时 `-Pit=true` 跑） | 必过（改动 T4/T5 时） |
| UI 冒烟 | Platform test framework 构造 `ServerStatusStrip`，驱动状态序列后 `dispatchAllInvocationEventsInIdeEventQueue`，断言显隐/按钮/凭据输入可用性 | 必过（3~5 例） |
| 真机手工 | §9.2 装机清单（含 Windows 进程树、双窗口引用计数、端口冲突三项） | 必过 |

---

## 11. 参考来源

**平台官方（依据强度：高）**

- `ProcessHandler` / 进程终止语义：https://plugins.jetbrains.com/docs/intellij/process-handler.html
- `GeneralCommandLine` 与执行框架：https://plugins.jetbrains.com/docs/intellij/execution.html
- Disposers and Disposable：https://plugins.jetbrains.com/docs/intellij/disposers.html
- Tool Windows（Content/`setDisposer`）：https://plugins.jetbrains.com/docs/intellij/tool-windows.html
- Threading Model：https://plugins.jetbrains.com/docs/intellij/threading-model.html
- Notifications：https://plugins.jetbrains.com/docs/intellij/notifications.html
- Plugin 模块化与 split mode：https://plugins.jetbrains.com/docs/intellij/modular-plugin-template.html

**本仓契约与既有实现（依据强度：高，可直接引用）**

- REST/SSE 契约与对账：《TSD-06-事件流接入设计》§4、§5.7、§9
- 界面布局与 Tool Window 形态：《TSD-07-主界面布局设计》§1、§2
- 会话面板生命周期与日志契约：《TSD-30-会话面板整体优化方案》§1.3、§4.2、§5.4、§9.2（G4）
- API 实证证据文件：`temp/opencode-rest-tool/openapi.json`

**本轮已消解的不确定项（详见 §12）**

- `NetUtils.findAvailableSocketPort()` 存在且未弃用；`SocketUtil` 不存在（A6）。
- `killProcessTree` 为 `OSProcessHandler` 的 `protected` 方法，外部不可调用（A3）。
- `intellij.platform.execution` 模块存在，backend 需新增声明（A8）。
- `GET /api/info` 契约**不含 503**（A14）。
- `OPENCODE_SERVER_URL` 非 opencode 官方环境变量，无冲突（A15）。

---

## 12. §12 API 核实结果（本机 SDK 2026.2.3 / opencode v2.0.18）

> 证据口径：`javap -classpath "<IDEA>/Contents/lib/*" <fqcn>`（SDK = IntelliJ IDEA 2026.2.3，build 262.10968.63，SDK 根 `/Applications/IntelliJ IDEA.app/Contents/lib`）；opencode 环境变量取自 `strings ~/.opencode/bin/opencode`；契约取自 `temp/opencode-rest-tool/openapi.json`。**javap 不含 Javadoc**——凡涉及「语义」（而非签名）的条目均标注「依据签名/枚举推断，语义需实测」。

| # | 核实项 | 结论 | 证据（类/签名 + jar） | 对设计的影响 |
|---|---|---|---|---|
| A1 | `GeneralCommandLine` 现行签名 | **可用**（无弃用） | `util.jar`：`withEnvironment(Map<String,String>)` / `withEnvironment(String,String)` / `withParentEnvironmentType(ParentEnvironmentType)` / `withWorkingDirectory(java.nio.file.Path)` / `withWorkDirectory(File|String)` / `withCharset(Charset)` / `withParameters(String...)` / `withParameters(List<String>)`，均返回 `GeneralCommandLine`；另 `isPassParentEnvironment()` / `getEffectiveEnvironment()` | §3.6 直接采用；工作目录用 `withWorkingDirectory(Path)` |
| A2 | `OSProcessHandler` 构造与启动/等待 | **可用** | `util.jar`：`OSProcessHandler(GeneralCommandLine)`（throws `ExecutionException`）、`(Process,String)`、`(Process,String,Charset)`、`(Process,String,Charset,Set<? extends File>)`；`startNotify()` 继承自 `BaseOSProcessHandler`（自有 `public void startNotify()`）；`waitFor()` / `waitFor(long)` 在 `OSProcessHandler` 与 `ProcessHandler` 上均有 | §3.6 采用 `(GeneralCommandLine)` 构造 + `startNotify()` + `waitFor()` |
| A3 | **`killProcessTree` 归属与签名** | **存在但不可外部调用**；`ProcessKillUtil` **未找到** | `util.jar`：`OSProcessHandler` 上为 `**protected** void killProcessTree(java.lang.Process)`（非 static、非 public）；同类的公开能力是 `public static boolean processCanBeKilledByOS(Process)`、`public void setShouldDestroyProcessRecursively(boolean)`、`protected boolean shouldDestroyProcessRecursively()`、`protected void doDestroyProcess()`。`com.intellij.execution.process.ProcessKillUtil` 与 `com.intellij.util.io.ProcessKillUtil` 均在 SDK 全量 jar 中**未找到**（`unzip -l` 全扫无命中） | **设计变更**：不得调用 `killProcessTree()`。改用 `KillableProcessHandler` + `setShouldDestroyProcessRecursively(true)`，以 `destroyProcess()`/`killProcess()` 完成终止（§3.6/§3.7） |
| A4 | `destroyProcess()` / `killProcess()` 签名与差异 | **可用**；语义**需实测** | `util-8.jar`：`ProcessHandler.destroyProcess()`（`public void`，内部调 `protected abstract destroyProcessImpl()`）、`isProcessTerminated()` / `isProcessTerminating()` / `isStartNotified()` / `notifyProcessTerminated(int)`。`util.jar`：`KillableProcessHandler` 继承 `OSProcessHandler`，`public void killProcess()`、`public boolean canKillProcess()`、`protected void destroyProcessImpl()`、`public void setShouldKillProcessSoftly(boolean)`；接口 `com.intellij.execution.KillableProcess`（`canKillProcess()`/`killProcess()`）、`SoftlyKillableProcessHandler`（`shouldKillProcessSoftly()`） | **语义差异（据签名推断，需实测）**：`destroyProcess()`=优雅停止、`killProcess()`=强杀（是否带走进程树取决于 `shouldDestroyProcessRecursively`）。§3.7 采用「destroy → 宽限 → kill」组合 |
| A5 | `ProcessListener` / `ProcessAdapter` / `ProcessOutputType` | **可用** | `util-8.jar`：`ProcessListener.onTextAvailable(ProcessEvent, com.intellij.openapi.util.Key)`（default 方法）、`processTerminated(ProcessEvent)`、`processWillTerminate(ProcessEvent, boolean)`、`processNotStarted()`、`startNotified(ProcessEvent)`；`ProcessAdapter`（abstract，无成员）；`ProcessOutputType` 含 `STDOUT` / `STDERR` / `SYSTEM`，并有静态 `isStdout(Key)`/`isStderr(Key)`；`ProcessEvent` 提供 `getText()` / `getExitCode()` | §3.6/§3.8 采用 `onTextAvailable` + `isStdout/isStderr` 区分流；`processTerminated` 做退出监听 |
| A6 | 空闲端口获取 | **`findAvailableSocketPort()` 可用、未弃用**；**`SocketUtil` 未找到** | `util.jar` `com.intellij.util.net.NetUtils`：`public static int findAvailableSocketPort() throws IOException`、`public static int tryToFindAvailableSocketPort()`、`tryToFindAvailableSocketPort(int)`、`findAvailableSocketPorts(int)`、`getProxySelector(String)`、`canConnectToSocket/isLocalhost/canConnectToRemoteSocket`（后三者标注 `@ApiStatus$Obsolete`；`findAvailableSocketPort()` **无** @Deprecated/@ApiStatus.Obsolete）。`com.intellij.util.net.SocketUtil` 与 `com.intellij.util.io.SocketUtil` 全量搜索**未找到** | T1 用 `NetUtils.findAvailableSocketPort*`；**不要**引用 `SocketUtil`（SDK 无此类） |
| A7 | `PathManager.getSystemPath()` | **可用** | `util-8.jar` `com.intellij.openapi.application.PathManager`：`public static String getSystemPath()`、`getSystemDir()`（返回 `java.nio.file.Path`）、`getConfigPath()`、`getLogPath()`、`getPluginsPath()` | T3 注册表落盘根用 `PathManager.getSystemPath()`（或 `getSystemDir()`） |
| A8 | 模块可得性（`opencode-backend`） | **无需新增依赖（v1.2 修正）**；`intellij.platform.execution` 反而**不可声明** | `javap -classpath "<IDEA>/Contents/lib/util.jar:<IDEA>/Contents/lib/util-8.jar"` 可解析出 `GeneralCommandLine` / `OSProcessHandler` / `KillableProcessHandler`（即这些类在 `util.jar`，属公开模块 `intellij.platform.util`）；沙箱实证：在 backend 的 `<dependencies>` 加 `<module name="intellij.platform.execution"/>` 后，测试沙箱启动报 `opencode-idea-panel.opencode-backend isn't loaded: it is from namespace 'com.ayongw...' and depends on module 'intellij.platform.execution' which is registered in 'com.intellij' plugin with internal visibility in namespace 'jetbrains'`（`PluginSetBuilder.kt:308`），插件整体不加载 | 保持 backend 依赖声明**不变**（`platform.backend` / `kernel.backend` / `rpc.backend` + shared），进程管理类经传递依赖即可用；`intellij.platform.execution` 一律**不得**出现（§6.1） |
| A9 | split mode 主机判定 | `ClientHost` / `RemoteApiProvider` **未找到**；**可用替代已定位** | `intellij.platform.core.jar`：`ClientSessionsUtil.getCurrentSessionOrNull(Project)` → `ClientProjectSession`（`ClientSession.isLocal()` / `isRemote()`）；`ClientKind{LOCAL,FRONTEND,REMOTE,…}`。`intellij.platform.projectModel.impl.jar`：`EelProviderUtil.getEelDescriptor(Project)`；`util-8.jar`/`lib`：`com.intellij.platform.eel.provider.LocalEelDescriptor.INSTANCE`（本机单例）。`util.jar`：`GeneralCommandLine.getNonLocalEelDescriptor()`（非本机时非 null） | R4 降级判定入口：`EelProviderUtil.getEelDescriptor(project) !== LocalEelDescriptor.INSTANCE`（或 `ClientSessionsUtil.getCurrentSessionOrNull(project).isLocal()`）即非本机 → 默认关闭自动启动 |
| A10 | 通知 API 与 `notificationGroup` 扩展点 | **可用** | `intellij.platform.ide.core.jar`：`NotificationGroupManager`（`getInstance()` / `getNotificationGroup(String)` / `isGroupRegistered(String)`）、`NotificationGroup`（`createNotification(...)`）、`Notification`、`NotificationType{INFORMATION,WARNING,ERROR,…}`。EP 实证样例（内置插件 jar 内 `META-INF/plugin.xml`）：`<notificationGroup id="Coverage" displayType="BALLOON" bundle="messages.JavaCoverageBundle" key="notification.group.coverage"/>` | §5.3 采用该写法在 **frontend 模块**注册 `<notificationGroup>`（bundle 指向 `OpencodeFrontendBundle`） |
| A11 | 子进程环境变量注入 | **可用**（父环境默认继承）；语义**需实测** | `util.jar`：`GeneralCommandLine.withEnvironment(String,String)` / `withEnvironment(Map)`；`GeneralCommandLine$ParentEnvironmentType{NONE, SYSTEM, CONSOLE}`；`isPassParentEnvironment()` / `getParentEnvironment()` / `getEffectiveEnvironment()`。opencode v2.0.18 二进制含 `OPENCODE_SERVER_PASSWORD` / `OPENCODE_PASSWORD`（`strings` 证据） | §3.6 采用 `withEnvironment("OPENCODE_SERVER_PASSWORD", pw)`，保持默认 `ParentEnvironmentType.CONSOLE` 以继承父环境；**「父进程注入即子进程可见」以签名/枚举推断，需实测**（A11 保留一条实测项） |
| A12 | 跨进程文件锁（`java.nio.channels.FileLock`） | **需运行时验证** | 无法静态核实：SDK 未改变 JDK 锁语义；`FileLock` 为 JDK 类型，多实例/跨平台（macOS/Windows）行为须实测 | §3.3/§3.7 保留 `FileLock`，但**必须有降级路径**：注册表「原子写（临时文件 + rename）+ 心跳收敛」，不依赖强互斥（R13） |
| A13 | 代理污染回环探测 | **平台提供关闭代理的现成能力**；`HttpRequests` 在 `com.intellij.util.io`（**非** `util.net`） | `intellij.platform.ide.core.jar`：`com.intellij.util.io.HttpRequests.request(String/Url)` → `RequestBuilder`，含 `public RequestBuilder useProxy(boolean)`、`connectTimeout(int)`、`readTimeout(int)`、`tuner(ConnectionTuner)`、`connect(RequestProcessor)`；`util.jar`：`NetUtils.getProxySelector(String)`；`com.intellij.util.net.ProxySettings`（接口，`getInstance()`）存在。`com.intellij.util.net.HttpRequests` **未找到**（类在 `util.io`） | §3.5 改用 JDK `HttpURLConnection` + `Proxy.NO_PROXY`（**显式关代理**，且不引入内部模块依赖）；okhttp（事件流）默认走 `ProxySelector.getDefault()`，回环是否被代理仍**需实测**（R3） |
| A14 | `GET /api/info` 未就绪是否返回 503 | **未在契约中声明（未证实）** | `temp/opencode-rest-tool/openapi.json`：`/api/info` 的 `get.responses` 仅 `200` / `400` / `401`（`/api/project` 同样仅 200/400/401），**无 503** | **设计变更**：「未就绪」不得依赖 503；就绪判定改为「2xx 且 JSON 且非 SPA」，未就绪=连接拒绝/超时/非 2xx/非 JSON（§3.5）。503 在测试桩中作为**额外健壮性形态**保留（应归类为 UNREACHABLE 或按实现约定） |
| A15 | `OPENCODE_SERVER_URL` 是否与官方冲突 | **无官方冲突**（官方未占用）；建议加插件前缀 | opencode v2.0.18 的 `OPENCODE_*` 环境变量清单（`strings` 全量）**不含** `OPENCODE_SERVER_URL`；清单含 `OPENCODE_SERVER_PASSWORD`、`OPENCODE_PASSWORD`、`OPENCODE_CONFIG_DIR`、`OPENCODE_DB` 等。官方 `opencode serve --help` 亦未声明该变量 | §3.3/§3.4 保留 `OPENCODE_SERVER_URL` 作为默认地址覆盖；**建议**改用插件自有前缀 `OPENCODE_IDEA_SERVER_URL` 以避免未来与官方语义潜在冲突（低风险，不阻塞） |

**仍无法静态核实、需运行时实测的条目（汇总）**

1. **A12** `FileLock` 多 IDE 实例 + macOS/Windows 行为（R13 必须准备降级方案）。
2. **A11** 「父进程 `withEnvironment` 注入的环境变量对子进程可见」的实际语义（含 `ParentEnvironmentType` 默认值实测）。
3. **A4** `destroyProcess()` 与 `killProcess()` 在 Windows 上是否带走子进程树、组合顺序（R1）。
4. **A13** 回环探测是否被 IDE `ProxySelector` 改写（已用 `useProxy(false)` 缓解，仍需实测确认 okhttp 事件流侧）。

---

## 13. 决策点（已定，2026-09-30）

| # | 决策点 | 已定方案 | 落点 |
|---|---|---|---|
| 1 | 是否自动启动 server | **默认自动启动**；探测到**已启动且可复用**的实例则复用，但**必须确保该实例是本插件启动的**（归属识别 = 共享注册表 + 记录中的 `pid` 与 `startedAt`/`pluginVersion` 归属标记，探测时 `info.pid == 登记 pid` 才认自有） | §2.1-1、§3.3、§6.1 |
| 2 | 他人启动的实例 | **允许接入，但需要用户输入对应密钥**（交互成本可接受）；探测时机 = **插件启动时** | §3.2（`NEEDS_CREDENTIALS`）、§3.3、§4.5、§5.1 |
| 3 | CLI 未安装 | **提醒并引导安装**：通知（可打开文档/官网）+ 设置页可填 CLI 路径；**不**代用户安装 | §2.1-5、§3.5（`CLI_NOT_FOUND`）、§5.2、§5.3 |
| 4 | 自有进程生命周期归属 | **引用计数**：同一 IDE 多工作区/多窗口共享同一 server；单个引用者关闭**不终止**；**最后一个引用者**关闭时才终止。引用计数存于共享注册表，含「最后一个引用者」判定与崩溃残留清理 | §3.7、§6.4、T10 |
| 5 | 端口策略 | 插件启动的与默认启动的**都用 4096**；冲突时启用**备用端口**；探测顺序 **默认端口 4096 → 备用端口** | §3.4、§3.5、§9.2 |

---

*文档结束*
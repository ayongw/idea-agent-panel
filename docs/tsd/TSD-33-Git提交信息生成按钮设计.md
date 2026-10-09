# TSD-33-Git 提交信息生成按钮设计

> 适用范围：idea-agent-panel 在 **IDE 提交窗口（Commit 工具窗 / Commit 对话框）** 增加一个「生成提交信息」按钮；用户**手工点击**后由 opencode 生成提交信息并回填输入框。
> 关联文档：面板整体与输入区见《TSD-07-主界面布局设计》《TSD-08-输入区与上下文交互设计》；进程与连接管理纪律见《TSD-31-进程与连接管理方案》；消息渲染与事件流见《TSD-06-事件流接入设计》《TSD-30-会话面板整体优化方案》。
> 契约依据：平台扩展点与数据通路均已对本机 **IntelliJ IDEA 2026.2.3（build #IU-262.10968.63）** 实测核对（§2），非凭记忆推断。

## 修订历史
| 版本 | 日期 | 变更说明 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-10-07 | 初版：确认 2024.2 起 `VcsCommitMessageInterceptor` 已移除、按钮只能走 Action 系统；坐实 `Vcs.MessageActionGroup` + `VcsDataKeys.COMMIT_MESSAGE_CONTROL` 官方通路；给出三层组织（backend 生成 / frontend 上下文+UI）、一次性会话防污染、diff 限长、失败降级与实施阶段 | agent |
| v1.1 | 2026-10-08 | 补「生成模型解析优先级」（用户明确要求：**默认取 opencode 默认模型或 free 模型**，见 §5.1.1），并把该来源纳入失败矩阵；回填 S1 真机验证结论（§12） | agent |
| v1.4 | 2026-10-08 | S3 落地：`promptBody` 支持指定模型；`CommitMessageGenerator` 一次性会话生成；模型**复用设置页已有的「默认模型」下拉**（不再新增重复设置项）；未配置即 balloon + 「去设置」跳转模型页 |
| v1.3 | 2026-10-08 | 按用户决策**简化模型选择**（§5.1.1）：只用用户指定的默认模型，未指定即引导去设置、**不做自动回退**（避免在用户不知情下消耗额度） |
| v1.2 | 2026-10-08 | S2 落地：分支 / 最近提交改为 **git CLI** 实现（2026.2 的 `VcsRepositoryManager` 已无 branch 方法、`VcsLog` 只能取选中项，旧 API 失效）；不引入 `.impl` 死依赖。回填分级限长实现细节与 S2 状态 | agent |

---

## 1. 结论与总览

在提交信息输入框所在区域加一个按钮，点击后：收集当前 changelist 的变更上下文 → 调 opencode 生成 → 回填输入框。**不做全自动填充**（用户已确认）。

```
┌───────────────────────────────────────────────────────────────────────┐
│ 提交消息                                          [✨ 生成]           │
│ ┌───────────────────────────────────────────────────────────────────┐ │
│ │ feat(chat): 切会话改为 id 差量更新，避免点击被吞                     │ │
│ │                                                                   │ │
│ └───────────────────────────────────────────────────────────────────┘ │
│ [V] 用户服务/…/ApplicationServiceImpl.java   [+新增]  [-删除]  [修改 3] │
└───────────────────────────────────────────────────────────────────────┘
        ↑ 点击 → 按钮转 loading → 生成 → setCommitMessage(...)
```

三层分工（各自落在已有模块，不新增模块）：

| 层 | 模块 | 职责 |
|---|---|---|
| 生成 | **opencode-backend** | 新增 RPC：调 opencode 生成提交信息；IO 线程 + 超时；一次性会话 |
| 上下文 | **opencode-frontend** | changelist 文件列表 + diff 摘要 + 分支 + 最近提交；限长 |
| UI | **opencode-frontend** | `AnAction` 注册进 `Vcs.MessageActionGroup`；loading → 回 EDT 回填 |

---

## 2. 平台契约实测结论（先证据，后方案）

### 2.1 `VcsCommitMessageInterceptor` 已移除 —— 不能用它插面板

对本机 `lib/*.jar` 内 `META-INF/VcsExtensionPoints.xml` 全量扫描，当前与提交信息相关的扩展点只有三个：

| 扩展点 | 能力 | 能否做按钮 |
|---|---|---|
| `vcs.commitMessageProvider`（`CommitMessageProvider`） | 打开对话框 / 切换 changelist 时**同步**返回初始提交信息 | ❌ 无 UI，且同步阻塞会卡住对话框 |
| `vcs.commitMessageInspection` | 提交信息校验 | ❌ 与生成无关 |
| `vcs.commitSuccessNotificationActionProvider` | 提交成功后的动作 | ❌ 时机不对 |

> ⚠️ `VcsCommitMessageInterceptor`（老做法：在提交信息框下方插一个面板）是 **2024.2 起废弃**，2026.2.3 已**不存在**。按老资料实现会直接查不到类。

### 2.2 按钮的唯一可行入口：Action 系统

`VcsActions.xml` 中存在真实可注册的 group id：

```xml
<group id="Vcs.MessageActionGroup">
```

平台自带的 `com.intellij.openapi.vcs.actions.ShowMessageHistoryAction` 就注册在这里（提交信息区域的「消息历史」按钮），所以「✨ 生成」放在**同一区域**，与用户预期一致。

### 2.3 回填提交信息字段的官方数据通路

均为本机 `javap` 实测的 **public API**（非反射、非内部类）：

```
com.intellij.openapi.wm.StatusBarWidget…            （无关）
com.intellij.openapi.vcs.VcsDataKeys
    public static final DataKey<CommitMessageI> COMMIT_MESSAGE_CONTROL
com.intellij.openapi.vcs.CommitMessageI
    public interface CommitMessageI { void setCommitMessage(String); }
```

用法（与平台自身 `ShowMessageHistoryAction` 同一路径）：

```kotlin
val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)  // CommitMessageI?
control?.setCommitMessage(generatedText)
```

### 2.4 split mode 模块依赖

`VcsDataKeys` / `CommitMessageI` 位于 `intellij.platform.vcs.jar`（platform-api）。前端模块需在 `idea-agent-panel.opencode-frontend.xml` 增加：

```xml
<dependencies>
    <module name="intellij.platform.frontend"/>
    <module name="intellij.platform.vcs"/>   <!-- 新增 -->
    <module name="idea-agent-panel.opencode-shared"/>
</dependencies>
```

并在 `build.gradle.kts` 的 `opencode-frontend` 分支加 `bundledModule("intellij.platform.vcs")`。

> ⚠️ 沿用现有约束：content module **不得**声明 `com.intellij.modules.platform`（见历史踩坑：split mode 下会报 internal 可见性错误），只能声明具体 bundled module。

---

## 3. 交互设计

### 3.1 状态机

| 状态 | 按钮 | 说明 |
|---|---|---|
| 空闲 | `✨ 生成` 可点 | 无 changelist 变更时禁用 |
| 生成中 | 转圈 / 禁用 | 后台 IO，主线程不做重活 |
| 成功 | 恢复 `✨ 生成` | 已回填；已有内容时**追加**到末尾 |
| 失败 | 恢复 + balloon | 失败不覆盖用户已输入的内容 |

### 3.2 回填策略：追加而非替换

用户手动写的提交信息**不能被生成结果抹掉**：

| 输入框当前内容 | 行为 |
|---|---|
| 空 | 直接填入生成结果 |
| 非空 | 追加到末尾（空一行分隔） |

### 3.3 不做全自动填充

不实现 `CommitMessageProvider`。理由：它在**打开对话框 / 切换 changelist 时同步调用**，会阻塞 UI，且每次开对话框都调一次模型既慢又浪费。用户已明确「手工点击后再生成并填充」。

---

## 4. 上下文收集（frontend）

### 4.1 采集内容

| 项 | 来源 | 限长策略 |
|---|---|---|
| 变更文件列表 | `ChangeListManager.getInstance(project).getChangeList(id)` 的 changes | 全量（只取路径 + 类型） |
| 变更统计 | `Change.getChangeType` / 行数 | 全量 |
| diff 摘要 | `ChangeDiffProvider` / `DiffContent` | **每文件前 K 行**，总量封顶 |
| 当前分支 | `GitRepositoryManager`（可选，Git 才取） | 1 行 |
| 最近提交信息 | `VcsLogProvider`（可选） | 最近 5 条，仅供学风格 |

> **分支名 / 最近提交：走 git CLI**（`GitCliHistory`，S2 已实现）。
>
> 原本想用 IDE API，实测 2026.2 下已失效：
> - `VcsRepositoryManager`（`intellij.platform.vcs.dvcs.impl`）**没有任何 revision / branch 方法**
>   （实测 24 个 public 方法全无）；
> - `VcsLog`（`intellij.platform.vcs.log`）只暴露"选中项"，遍历历史要 `VcsLogManager`（`.log.impl`）。
>
> 那几个 impl 模块作为 compile 依赖**能解析**（不增加包体积、不内置），但旧 API 已失效，
> 留着只是死依赖 + 脆弱性，故**不引入**，改用 git CLI 退化实现：
> - 分支：`git rev-parse --abbrev-ref HEAD`（detached HEAD 退化为短 SHA）
> - 最近提交：`git log -N --no-merges --format=%s`（只取标题，正文对学语气无价值）
> - 非 Git 目录 / git 不在 PATH / 超时 → 安静返回空，prompt 自动省略对应段落
>
> ⚠️ git CLI 会起子进程，采集必须在 **IO 线程**执行。

### 4.2 diff 限长（关键防爆）

长 diff 直接塞进去会 token 爆炸且极慢。分级截断：

```
分级策略（S2 已实现，见 CommitMessageDiffLimiter）：
1. 单文件 > 500 行            → 只给 stat（路径 + +/- 行数），不给 diff
2. 单文件 > 120 行            → 截到 120 行，标注「该文件 diff 已截断」
3. 剩余全局额度不足           → 截到剩余额度，标注截断
4. 全局额度（800 行）用尽     → 其余文件退化为只给 stat
```
> 三级判定顺序固定：先看单文件上限，再看全局额度。缺一级会让 200 行文件原样进prompt
> （已被单测抓到过一次，见 §12）。

同时 prompt 里明确「以下是节选」，避免模型误判全貌。

### 4.3 组装 prompt

```
为下列代码变更生成一条 Git 提交信息。
要求：Conventional Commits 格式；正文用中文；只描述做了什么、为什么；不写客套话。

分支：feat/xxx
最近提交（学习语气）：
- fix(chat): ...
- feat(settings): ...

变更文件（3 个）：
- M  src/main/kotlin/.../SessionTabs.kt  (+42 -18)
- A  src/test/kotlin/.../SessionTabsDiffUpdateUnitTest.kt  (+120)
- M  src/main/resources/...properties  (+2)

diff（节选）：
--- a/SessionTabs.kt
+++ b/SessionTabs.kt
@@ ...
```

---

## 5. 生成侧（backend）

### 5.1 新增 RPC

```kotlin
// opencode-shared ChatRepositoryRpcApi
suspend fun generateCommitMessage(projectId: ProjectId, request: CommitMessageRequestDto): CommitMessageResultDto
```

| DTO | 字段 |
|---|---|
| `CommitMessageRequestDto` | `prompt: String`、`modelId: String?`、`providerId: String?` |
| `CommitMessageResultDto` | `text: String`、`sessionId: String?`、`truncated: Boolean` |

### 5.1.1 生成模型：只用用户指定的默认模型 ⚠️（v1.3 简化）

用户决策：**简化** —— 不做多级回退，只用"用户指定的默认模型"。

| 情况 | 行为 |
|---|---|
| 设置里已指定默认模型 | 直接用它生成 |
| **未指定** | **不发起任何请求**，弹出提示引导去设置，并提供「去设置」跳转 |

理由：自动回退到 free / 任意可用模型看似"更聪明"，实际会让用户在毫不知情的情况下
被消耗额度（默认模型可能不免费），且生成结果与用户预期不符。**显式配置 + 缺失即引导**
更可预期，也让"为什么这次生成花了钱"这类问题不存在。

未指定时的提示形态：balloon 通知，正文说明「请先在设置里指定提交信息生成模型」，
附带一个 **NotificationAction「去设置」** 跳转到设置页对应位置。

### 5.2 一次性会话，不污染历史列表 ⚠️

生成需要走一次 opencode 对话。若复用「新会话」默认行为，会在用户历史会话列表里留下一堆 `New Session` —— 项目此前已专门修过「空会话重复创建」，不能再踩。

约束：
1. 用 `createSession(title = "commit-message-<ts>", directory = <临时目录>)`，**directory 不落在项目工作区**；
2. 生成结束后立即 `deleteSession`；
3. delete 失败只记日志，不阻塞（列表刷新时会过滤掉非工作区会话）。

### 5.3 执行纪律

| 项 | 约束 |
|---|---|
| 线程 | 后端 `Dispatchers.IO`，**禁止**在 EDT 上跑模型调用 |
| 超时 | 60s，超时按失败处理 |
| 并发 | 同一项目同时只允许一个生成任务（重复点击直接忽略） |
| 取消 | 用户再次点击 / 提交时取消进行中的生成 |

---

## 6. 设置项（frontend，复用 `AgentSettingsState`）

| 设置 | 默认 | 说明 |
|---|---|---|
| **提交信息生成模型** | — | **复用设置页已有的「默认模型」下拉**（opencode GLOBAL 配置），不新增设置项；留空（选「不设置」）时生成按钮给出引导提示（§5.1.1），**不做自动回退** |
| 提交信息语言 | 中文 | 正文语言 |
| diff 上限（行） | 800 | 对应 §4.2 |
| 包含未暂存变更 | 关 | 勾上则一并纳入上下文 |

---

## 7. 失败与降级矩阵

| 失败点 | 现象 | 降级 |
|---|---|---|
| Server 未就绪 | `getServerState` 非 READY | balloon 提示「请先启动 Server」+ 打开设置入口 |
| 生成超时 / 报错 | 无结果 | balloon 展示后端 `detail`，**不覆盖**输入框已有内容 |
| 无 changelist | 按钮禁用 | 置灰 + tooltip 说明 |
| `COMMIT_MESSAGE_CONTROL` 取不到 | 不回填 | 日志告警（说明不在提交信息区域，插件当前不支持） |
| 非 Git 仓库 | 无最近提交/分支 | 降级为仅用文件列表 + diff |
| **未配置生成模型** | 无模型可用 | 按 §5.1.1 **不发起请求**；balloon 提示 + 「去设置」跳转 |

---

## 8. 实施阶段（可独立验证）

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **S1** | 加平台依赖（`intellij.platform.vcs`）+ 打通 `AnAction` 注册进 `Vcs.MessageActionGroup`，按钮点击只打日志 | 提交窗口出现按钮，点击有日志 |
| **S2** | 上下文收集 + 限长，组装 prompt（先不调模型），日志打印最终 prompt | 单测 + 日志核对 prompt 内容与截断 |
| **S3** | backend RPC + 一次性会话生成 + 超时/并发/取消 | ITest（需活 opencode 实例） |
| **S4** | 回填（追加策略）+ 失败 balloon + 设置项 | 手工冒烟 |

---

## 9. 验证方式

### 9.1 自动化
- `CommitMessageContextBuilderUnitTest`：diff 限长（超限截断、省略行数、分级降级为 stat）
- `CommitMessagePromptUnitTest`：prompt 结构、Conventional Commits 约束、语言指令
- `CommitMessageResponseParseUnitTest`：后端响应解析（空响应 / 超长 / 带围栏）

### 9.2 手工冒烟（需真机 + 活 opencode 实例）
1. 提交窗口点「✨ 生成」→ 转圈 → 输入框被填充
2. 输入框已有内容 → 生成结果追加在下方，不覆盖
3. 停掉 opencode server → 点击 → balloon 提示，输入框内容不变
4. 关闭 Chat 工具窗后再点生成 → 仍可用（不依赖工具窗生命周期）
5. 生成完成后历史会话列表**不应**出现 `New Session`

---

## 10. 边界与不做项

| 不做 | 原因 |
|---|---|
| 全自动填充（`CommitMessageProvider`） | 同步阻塞卡对话框；用户已确认只做手工触发 |
| 多个候选让用户挑 | 首版直接回填，控制范围；后续可加 |
| 读取暂存区内容逐行评审 | 首版只做「生成信息」，不做 code review |
| 服务端持久化生成结果 | 无必要，会话本身可查 |
| 非 Git 的其他 VCS 分支/最近提交 | 首版 Git 优先，其余降级 |

---

## 11. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| `Vcs.MessageActionGroup` 在未来版本改名/移除 | 按钮消失 | 集中在 `OpenCodeGenerateCommitMessageAction` 一处注册，便于改挂载点 |
| opencode 生成质量不稳定 | 提交信息不合用 | 提示词约束 + 用户可手工编辑；失败不覆盖 |
| 一次性会话未清理 | 历史列表残留 | delete 失败仅记日志；列表按工作区目录过滤兜底 |
| 平台依赖声明错误导致插件加载失败 | 插件不可用 | 只加具体 bundled module，**不加** `com.intellij.modules.platform`；S1 先单独验证插件可加载 |

---

## 12. 实施状态

| 阶段 | 状态 | 备注 |
|---|---|---|
| S1 | ✅ **已完成（含真机验证）** | 已加 `intellij.platform.vcs` 依赖（Gradle + 模块描述）、`GenerateCommitMessageAction` 注册进 `Vcs.MessageActionGroup`。真机日志三次点击均触发、`提交信息控件=可达`（`2026-10-08 14:14:28/37/55`），确认挂载点与数据通路均正确。**按钮当前只打日志，S2–S4 接入后才有实际效果** |
| S2 | ✅ **已完成** | 纯逻辑三件套 + 采集层 + git CLI：`CommitMessageContext`（模型）、`CommitMessageDiffLimiter`（分级限长）、`CommitMessagePromptBuilder`（组装）、`CommitMessageContextCollector`（VCS 采集）、`GitCliHistory`（分支 / 最近提交）。21 个单测覆盖分级边界（=500 / >500、>120、全局额度用尽）、prompt 结构与 git 输出解析。按钮尚未接线，需 S3 才生效 |
| S3 | ✅ **代码完成，待真机联调** | `CommitMessageRequestDto` / `CommitMessageResultDto` + RPC `generateCommitMessage`；`promptBody` 支持指定 `model{providerID,modelID}`；`CommitMessageGenerator` 走**一次性会话**（系统临时目录，生成完即删，不污染工作区历史列表）、60s 超时、失败以 `reason` 分类回传。模型取**设置页已有的「默认模型」**（`getDefaultModel`），未配置则不发起请求 |
| S4 | 部分完成 | 已做：回填（`setCommitMessage`）、未配置模型的 balloon + 「去设置」跳转（直接落到「模型」页）、失败 balloon。**未做**：生成中按钮 loading 态、多候选、diff 上限/语言等细粒度设置项 |
# OpenCode IDEA Panel

[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)]()
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2026.2.3-orange.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)]()

IntelliJ IDEA 插件，在 IDE 内集成 [OpenCode](https://opencode.ai/) AI 编程助手，提供原生 Tool Window 界面，支持流式对话、代码块渲染、权限确认、上下文注入等完整功能。

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| **原生 Tool Window** | 右侧边栏面板，纯 Swing 实现，零额外依赖，启动极快 |
| **流式对话** | 回复与思考过程按事件流逐字上屏（后端 75ms 节流），Markdown 实时渲染 |
| **代码块高亮** | `EditorTextField` 真实编辑器组件，语法高亮与主编辑器一致 |
| **思考过程** | 可折叠面板展示 AI 推理过程，流式期间自动展开 |
| **权限确认** | 输入区上方确认条（非模态，事件流驱动）：允许一次 / 始终允许 / 拒绝 |
| **工具调用卡片** | 消息区展示工具调用与结果（工具名、状态、入参、输出、退出码），运行中实时更新，重连 / 切换会话后仍可回看 |
| **上下文注入** | 当前文件、选中代码、光标位置、显式添加文件一键注入 |
| **会话管理** | 左侧会话列表，支持创建、切换、删除、重命名 |
| **用量与上下文占比** | 输入框下方展示当前会话 token 用量（含缓存）与上下文占用比例，接近窗口上限时警示 |
| **本地 Server 管理** | 默认自动启动/复用 `opencode serve`（含他人实例的密钥接入），就绪探测、引用计数共享、优雅终止与自愈；面板内有状态条与重试入口 |

---

## 🏗 架构设计

### 模块划分

```
opencode-idea-panel/
├── opencode-shared/      # 跨模块契约：DTO、RPC 接口、事件模型、序列化器
├── opencode-frontend/    # UI 层：Tool Window、Swing 组件、ViewModel、状态管理
└── opencode-backend/     # 业务层：Server 进程管理、REST/SSE 客户端、消息状态、上下文收集
```

### 数据流向

```
用户输入 → Frontend ViewModel → Backend REST Client → OpenCode Server
                                                    │
                    SSE 事件流 ←───────────────────┘
                            ↓
              Backend 事件处理 → Frontend StateFlow → UI 渲染
                            ↑
              REST 对账 (GET /api/session/{id}/message) ─┘
```

---

## 🚀 快速开始

### 环境要求
- IntelliJ IDEA 2026.2+（构建与验证目标为 2026.2.3，`since-build=262`）
- JDK 21 (系统默认)
- **JBR 25** (项目自动使用 IDE 內建，路径: `/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/`)
- **`opencode` CLI**：已安装并可在 PATH 中找到；未安装时插件会给出引导通知，
  也可在 `Settings → OpenCode → Connection → Server management` 中直接填写 CLI 路径（插件不代为安装）

### 构建插件

```bash
# 克隆项目
git clone https://github.com/ayongw/opencode-idea-panel.git
cd opencode-idea-panel

# 设置项目专用 JDK (JBR 25)，不影响系统默认 JDK 21
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"

# 编译
./gradlew compileKotlin --no-daemon --no-configuration-cache

# 打包插件
# 版本形如 0.1.0.<构建号>，构建号默认取 git 提交数，产物：build/distributions/opencode-idea-panel-0.1.0.<构建号>.zip
./gradlew buildPlugin --no-daemon --no-configuration-cache

# 指定构建号（覆盖 git 提交数）
./gradlew buildPlugin -PbuildNumber=42 --no-daemon --no-configuration-cache
```

> 插件版本号与产出的 zip 名都会带上构建号，便于区分每次打包；插件的变更说明取自根目录 `CHANGELOG.md`，打包时自动转成 `plugin.xml` 的 `<change-notes>`（显示在 IDEA 的 Plugins → What's New）。

### 安装插件

1. 打开 IntelliJ IDEA
2. `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk`
3. 选择 `build/distributions/opencode-idea-panel-0.1.0.<构建号>.zip`
4. 重启 IDE

### 运行沙箱调试 (开发用)

```bash
# 启动带插件的沙箱 IDE
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew runIde --no-daemon --no-configuration-cache
```

---

## 📦 依赖版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| IntelliJ Platform | 2026.2.3（最低支持 2026.2） | 插件开发框架 |
| Kotlin | 2.3.20 | 主开发语言 |
| Gson | IDE 自带（2026.1 起为 2.13.x） | JSON 序列化，`compileOnly` 不随插件打包 |
| kotlinx-serialization | 1.9.0 | RPC DTO 序列化 |
| HTTP 客户端（REST） | JDK `HttpClient` / `HttpURLConnection`（内置） | 当前实现的 OpenCode Server REST 调用 |
| OkHttp | 4.12.0 | SSE 事件流（`/api/event`）客户端，随 `opencode-backend` 打包 |
| okhttp-sse | 4.12.0 | 事件流帧解析（`EventSource`），随 `opencode-backend` 打包 |
| JetBrains Markdown | 0.7.3 | Markdown → HTML 渲染 |
| 进程管理（`opencode serve`） | 平台 `intellij.platform.util`（`GeneralCommandLine` / `KillableProcessHandler`） | 随 `intellij.platform.backend` 传递可得，**无需**声明 internal 的 `intellij.platform.execution`（见 [TSD-31](docs/tsd/TSD-31-进程与连接管理方案.md) §12 A8） |

---

## 🛠 开发指南

### 项目结构

```
.
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 模块包含声明
├── gradle.properties             # Gradle/JDK 配置
├── src/main/resources/META-INF/plugin.xml  # 插件入口
├── opencode-shared/              # 共享契约模块
│   ├── build.gradle.kts
│   ├── src/main/resources/modular.plugin.shared.xml
│   └── src/main/kotlin/com/ayongw/idea/opencode/shared/
│       ├── ChatMessage.kt        # 消息实体
│       ├── ChatRepositoryRpcApi.kt  # RPC 接口
│       ├── dtos.kt               # 数据传输对象
│       └── serializers.kt        # 序列化器
├── opencode-frontend/            # 前端 UI 模块
│   ├── build.gradle.kts
│   ├── src/main/resources/modular.plugin.frontend.xml
│   ├── src/main/resources/icons/opencode.svg
│   └── src/main/kotlin/com/ayongw/idea/opencode/frontend/
│       ├── toolWindow/OpenCodeToolWindowFactory.kt
│       ├── chatApp/OpenCodeChatApp.kt
│       ├── chatApp/ui/           # UI 组件
│       └── chatApp/viewmodel/    # 视图模型
└── opencode-backend/             # 后端业务模块
    ├── build.gradle.kts
    ├── src/main/resources/modular.plugin.backend.xml
    └── src/main/kotlin/com/ayongw/idea/opencode/backend/
        ├── BackendRpcApiProvider.kt
        ├── BackendChatRepositoryModel.kt
        ├── BackendChatRepositoryRpcApi.kt
        ├── server/               # Server 运行时：发现/探测/拉起/终止/共享注册表/自愈
        └── repository/           # 业务逻辑
```

### 关键技术点

| 场景 | 实现方案 |
|------|----------|
| Server 进程管理 | `KillableProcessHandler`（`OSProcessHandler` 子类，递归销毁）+ 端口 4096→备用端口 + `GET /api/info` 就绪探测；自有实例经共享注册表引用计数（多窗口关最后一个才停）、自愈重启上限 3 次/10 分钟（见 [TSD-31](docs/tsd/TSD-31-进程与连接管理方案.md)） |
| SSE 事件流 | `okhttp-sse` EventSource 客户端（端点 `/api/event`，指数退避重连 + 读超时存活判定）已接入会话状态：流式内容与执行态经 RPC 推到面板（见 [TSD-06](docs/tsd/TSD-06-事件流接入设计.md)） |
| 流式渲染 | 后端按事件流累积内容并 75ms 节流推送，前端按消息 id 就地重渲染气泡（气泡内容未变则跳过） |
| 代码块渲染 | `EditorTextField` (真实编辑器) + `JBHtmlPane` (文本) |
| 跨进程通信 | Fleet RPC (`@Rpc` 接口 + `RemoteApiProvider`) |
| 上下文收集 | `Editor`/`PsiFile`/`Project` API + 右键菜单 Action |

---

## 📝 文档

- [环境初始化指南](docs/tasks/001%20%E7%8E%AF%E5%A2%83%E5%88%9D%E5%A7%8B%E5%8C%96.md)
- [产品需求文档 (PRD)](docs/prd/%E4%BA%A7%E5%93%81%E8%AF%B4%E6%98%8E.md)
- [技术方案设计 (TSD)](docs/tech/%E6%8A%80%E6%9C%AF%E6%96%B9%E6%A1%88.md)
- [设置管理设计 (TSD-05)](docs/tsd/TSD-05-%E8%AE%BE%E7%BD%AE%E7%AE%A1%E7%90%86%E8%AE%BE%E8%AE%A1.md)
- [事件流接入设计 (TSD-06)](docs/tsd/TSD-06-%E4%BA%8B%E4%BB%B6%E6%B5%81%E6%8E%A5%E5%85%A5%E8%AE%BE%E8%AE%A1.md)
- [主界面布局设计 (TSD-07)](docs/tsd/TSD-07-%E4%B8%BB%E7%95%8C%E9%9D%A2%E5%B8%83%E5%B1%80%E8%AE%BE%E8%AE%A1.md)
- [会话面板整体优化方案 (TSD-30)](docs/tsd/TSD-30-%E4%BC%9A%E8%AF%9D%E9%9D%A2%E6%9D%BF%E6%95%B4%E4%BD%93%E4%BC%98%E5%8C%96%E6%96%B9%E6%A1%88.md)
- [进程与连接管理方案 (TSD-31)](docs/tsd/TSD-31-%E8%BF%9B%E7%A8%8B%E4%B8%8E%E8%BF%9E%E6%8E%A5%E7%AE%A1%E7%90%86%E6%96%B9%E6%A1%88.md)
- [已归档：M1–M4 历史任务分解](docs/archived/)

---

## 🤝 贡献

1. Fork 本仓库
2. 创建特性分支: `git checkout -b feat/amazing-feature`
3. 提交变更: `git commit -m 'feat: add amazing feature'`
4. 推送分支: `git push origin feat/amazing-feature`
5. 发起 Pull Request

---

## 📄 许可证

Apache License 2.0 - 详见 [LICENSE](LICENSE)

---

## 🔗 相关链接

- [OpenCode 官网](https://opencode.ai/)
- [OpenCode GitHub](https://github.com/opencode-ai/opencode)
- [IntelliJ Platform SDK 文档](https://plugins.jetbrains.com/docs/intellij/)
- [JetBrains Runtime (JBR)](https://github.com/JetBrains/JetBrainsRuntime)
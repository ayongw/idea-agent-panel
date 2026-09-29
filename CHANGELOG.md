# Changelog

本文件记录 OpenCode AI Assistant Panel 的重要变更，构建时会被转换为插件的变更说明（IDEA 的 Plugins → What's New）。
版本标题需与 `build.gradle.kts` 的 `baseVersion` 一致，构建号（`0.1.0.<构建号>`）不写进标题。

## [0.1.0]

### Added

- 设置页：连接、模型、规则、技能、MCP 五个 Tab
- 模型：供应商增删改、默认模型切换、apiKey 写入
- 规则：`AGENTS.md` 直接编辑（全局 / 项目）
- 技能：`skills` 目录与 URL 列表管理，展示已发现技能
- MCP：`mcp.servers` 增删改与运行状态、`mcp.timeout` 配置
- 配置写入：JSONC 定点修改（保留注释与缩进），写前备份 `.bak`，原子落盘
- 设置项支持全局 / 项目两级作用域（默认全局）
- 会话界面：顶部会话 Tab 与底部工具条（审核类型 / 模式 / 模型）
- 会话用量：输入框下方展示当前会话 token 用量与上下文占比，接近窗口上限时警示
- 事件流：接入 `/api/event`（`okhttp + okhttp-sse`）事件客户端与事件帧解析，支持自动重连
- 对接 opencode v2：`/api` 前缀、HTTP Basic 认证、消息联合类型

### Fixed

- 修复 PATCH 请求无法发送导致的问题（会话重命名、shell 写入失效）
- 修复配置/响应解析中缺键时抛 NullPointerException
# Agent 适配层

本目录是**插件与具体 agent 之间的物理边界**。面板的中立能力由
`shared` 模块的契约定义（`ChatRepositoryRpcApi` / `ChatMessage` / `ToolCallDto` …），
每个 agent 在本目录下有一个自己的实现包，把自己的协议翻译成中立契约。

```
backend/
├── BackendChatRepositoryModel.kt   # 门面（agent 无关的外壳，委托给下面的实现）
├── BackendChatRepositoryRpcApi.kt  # RPC 适配
└── agent/
    └── opencode/                   # OpenCode 实现（当前唯一）
        ├── event/                  # SSE 客户端 + 事件解析 + 对账
        ├── mcp/                    # MCP 客户端（opencode 侧能力）
        ├── repository/             # REST 客户端 + 会话目录 + MessageMapper（协议→中立模型）
        └── server/                 # opencode serve 进程管理（发现/拉起/共享注册表/自愈）
```

## 新增一个 agent 时

1. 新建 `agent/<agentName>/`，放该 agent 的协议客户端与进程/连接管理
2. 在该包内完成「协议 → `shared` 中立模型」的翻译（参照 `opencode/repository/MessageMapper.kt`）
3. 能力集与 opencode 不同（无 MCP、无用量、无权限确认…）时，在 `shared` 下新增独立接口，
   **不要**在既有接口里塞 agent 专属参数
4. 在 `backend` 根的门面里按 agent 分发（选择器逻辑在那一刻才写，此时才有真实需求可依据）

## 不要做的事

- 不要把 agent 专属类型（opencode 的 `OpenCodeEvent` / `OpenCodeSession` / SSE 帧结构…）
  上浮到 `shared` 或 UI 层——那样每个新 agent 都要动 UI，隔离就白做了
- 不要在只有一个实现时就抽象 SPI / 工厂 / 注册表：没有第二个实现来验证抽象是否正确，
  过早抽象通常要推倒重来。当前靠目录结构表达边界，等第二个 agent 真正接入时再引入接口

package com.ayongw.idea.opencode.backend.server

/**
 * OpenCode Server 生命周期状态（TSD-31 §3.2）
 *
 * 状态转移由 [OpenCodeServerManager] 唯一驱动；UI 与连接层只读该状态，不自行判断进程归属。
 */
enum class OpenCodeServerState {
    /** 尚未决定端点（Manager 未初始化） */
    IDLE,

    /** 按候选顺序探测中（只读，无副作用） */
    DISCOVERING,

    /** 端点已可用且非本插件拉起（或本插件其他窗口拉起），只读复用 */
    REUSING,

    /** 命中他人启动的实例但当前凭据不可用（401/403），等待用户提供密钥 */
    NEEDS_CREDENTIALS,

    /** 已拉起子进程（或正准备拉起），正在轮询就绪 */
    STARTING,

    /** 端点可用，已下发给连接层 */
    READY,

    /** 用户可见的失败，携带 [OpenCodeServerFailure] 分类与输出尾巴 */
    FAILED,

    /** 正在释放引用（引用计数归零时才真正终止进程） */
    STOPPING,

    /** 终态：项目已关闭或用户显式停止 */
    STOPPED,
}

/**
 * 失败分类（TSD-31 §3.5）
 *
 * 必须是可观测的枚举值（进日志与 RPC），UI 文案由「分类 + 补充信息」拼装。
 */
enum class OpenCodeServerFailure {
    /** 找不到 `opencode` CLI（PATH 无命中且未配置 cliPath） */
    CLI_NOT_FOUND,

    /** 端口被占用：子进程绑定失败，或就绪探测发现的 pid 与子进程不符（争用） */
    PORT_IN_USE,

    /** 认证失败（401/403）：他人实例转 NEEDS_CREDENTIALS，自有实例直接 FAILED */
    AUTH_FAILED,

    /** 子进程存活但总超时内未就绪 */
    READY_TIMEOUT,

    /** 子进程在就绪前退出 */
    PROCESS_EXITED,

    /** 连接被拒 / 超时 / 响应非 JSON 等无法确认端点可用的情况 */
    UNREACHABLE,
}
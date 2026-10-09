package com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel

/**
 * 审核类型（审批策略）
 *
 * 注：opencode v2 暂无会话级审批策略端点（策略在 opencode config 的 permission 字段），
 * 当前为本地状态，待确认配置写入方式后再真正生效。
 */
enum class ApprovalMode(val labelKey: String) {
    /** 自动审批：工具调用按 agent 默认权限执行 */
    AUTO("chat.approval.auto"),

    /** 每次询问：敏感操作逐个确认 */
    ASK("chat.approval.ask"),

    /** 全部允许：不再弹权限确认 */
    ALLOW_ALL("chat.approval.allowAll")
}
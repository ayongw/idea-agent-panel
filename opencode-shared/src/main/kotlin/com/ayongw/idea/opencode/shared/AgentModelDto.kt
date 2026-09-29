package com.ayongw.idea.opencode.shared

import kotlinx.serialization.Serializable

/**
 * Agent（模式）信息，对应 opencode v2 `GET /api/agent` 的 Agent.Info
 */
@Serializable
data class AgentDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: String? = null
)

/**
 * 模型信息，对应 opencode v2 `GET /api/model` 的 Model.Info
 *
 * @param contextWindow 上下文窗口（`limit.context`），用于会话上下文占比的分母；未知为 null
 * @param providerName 供应商展示名（由 `GET /api/provider` 关联补齐）；未知为 null
 * @param free 免费模型（`cost` 各档单价均为 0）
 */
@Serializable
data class ModelDto(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String,
    val contextWindow: Long? = null,
    val providerName: String? = null,
    val free: Boolean = false
)

/** 供应商及其模型（模型选择弹窗按此分组） */
@Serializable
data class ModelProviderDto(
    val id: String,
    val name: String,
    val models: List<ModelDto>
)
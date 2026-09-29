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
 */
@Serializable
data class ModelDto(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String
)
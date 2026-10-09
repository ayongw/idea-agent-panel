package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import java.util.Base64

/**
 * opencode v2 的 HTTP Basic 认证头构造（REST 与事件流共用）
 *
 * 用户名默认 `opencode`；密码为空时不鉴权（返回 null）。
 */
object OpenCodeAuth {

    const val DEFAULT_USERNAME = "opencode"

    /** 构造 `Authorization` 头取值，密码为空时返回 null */
    fun basicHeader(username: String, password: String?): String? {
        val secret = password?.takeIf { it.isNotBlank() } ?: return null
        val credentials = "$username:$secret".toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
    }
}
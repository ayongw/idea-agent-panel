package com.ayongw.idea.opencode.backend.server

import java.net.URI

/** 端点来源（决定 UI 提示与「绑定端口」取值路径，见 TSD-31 §3.4） */
enum class OpenCodeServerEndpointSource {
    /** 用户显式配置的非默认地址 */
    SETTINGS,

    /** 环境变量 `OPENCODE_SERVER_URL` 覆盖的默认地址 */
    ENV,

    /** 默认地址（默认端口 4096） */
    DEFAULT_PORT,

    /** 备用端口候选（4097 起） */
    FALLBACK_PORT,
}

/**
 * 一个可用于连接 opencode server 的端点
 *
 * [owned] 表示该实例由本插件拉起（归属判据见 TSD-31 §3.3）；非自有端点只允许复用，永不终止。
 */
data class OpenCodeServerEndpoint(
    val baseUrl: String,
    val username: String,
    val password: String?,
    val port: Int,
    val source: OpenCodeServerEndpointSource,
    val owned: Boolean,
) {
    /** 日志/UI 展示用地址：剥离 userinfo，避免误填 `http://user:pass@host` 时泄漏凭据（§4.4） */
    val displayUrl: String get() = OpenCodeServerUrls.stripUserInfo(baseUrl)

    /** 覆盖 data class 默认 toString：密码只显示占位，防止凭据随日志外泄（§4.4） */
    override fun toString(): String =
        "OpenCodeServerEndpoint(baseUrl=$displayUrl, username=$username, " +
            "password=${if (password.isNullOrEmpty()) "<empty>" else "<redacted>"}, " +
            "port=$port, source=$source, owned=$owned)"
}

/** 端点地址解析工具（纯 JDK，无 IDE 依赖） */
object OpenCodeServerUrls {

    /** 解析 URL 端口；无显式端口时按 scheme 回落 80/443，非法 URL 返回 null */
    fun portOf(baseUrl: String?): Int? {
        val value = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val uri = URI(value)
            if (uri.host.isNullOrBlank()) return null
            when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("http", ignoreCase = true) -> 80
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> null
            }
        }.getOrNull()
    }

    /** 剥离 userinfo（`http://user:pass@host:port` → `http://host:port`） */
    fun stripUserInfo(baseUrl: String): String {
        val value = baseUrl.trim()
        return runCatching {
            val uri = URI(value)
            if (uri.userInfo == null) {
                value
            } else {
                URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            }
        }.getOrDefault(value)
    }

    /** 构造回环地址（新实例绑定与备用端口候选共用） */
    fun loopback(port: Int): String = "http://127.0.0.1:$port"
}
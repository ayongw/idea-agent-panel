package com.ayongw.idea.opencode.backend.server

/**
 * 地址来源优先级解析（TSD-31 §3.4）
 *
 * 纯逻辑：不触碰 IDE 与网络；默认地址、备用端口、环境变量取值均可注入，便于单测覆盖优先级矩阵。
 *
 * 规则：
 * - **探测顺序**：显式设置项 → 默认地址（`OPENCODE_SERVER_URL` 可覆盖）→ 备用端口（4097 起），按 baseUrl 去重；
 * - **绑定端口**：用户显式给了非默认端口则只用该端口（失败即报端口占用，不静默换端口）；否则 4096 → 备用端口。
 */
class OpenCodeServerEndpointResolver(
    private val defaultUrl: String = DEFAULT_SERVER_URL,
    private val fallbackPorts: List<Int> = defaultFallbackPorts(),
    private val env: (String) -> String? = { System.getenv(it) },
) {

    /** 探测候选列表（顺序即探测顺序） */
    fun discoveryCandidates(
        settingsUrl: String?,
        username: String,
        password: String?,
    ): List<OpenCodeServerEndpoint> {
        val candidates = LinkedHashMap<String, OpenCodeServerEndpoint>()

        explicitSettingsUrl(settingsUrl)?.let { url ->
            candidates[url] = endpoint(url, username, password, OpenCodeServerEndpointSource.SETTINGS)
        }

        val envUrl = normalized(env(ENV_SERVER_URL))
        val effectiveDefault = envUrl ?: normalized(defaultUrl) ?: DEFAULT_SERVER_URL
        candidates.getOrPut(effectiveDefault) {
            endpoint(
                effectiveDefault,
                username,
                password,
                if (envUrl != null) OpenCodeServerEndpointSource.ENV else OpenCodeServerEndpointSource.DEFAULT_PORT,
            )
        }

        fallbackPorts.forEach { port ->
            val url = OpenCodeServerUrls.loopback(port)
            candidates.getOrPut(url) {
                endpoint(url, username, password, OpenCodeServerEndpointSource.FALLBACK_PORT)
            }
        }

        return candidates.values.toList()
    }

    /** 拉起自有实例时的绑定端口候选（顺序即尝试顺序） */
    fun bindPortCandidates(settingsUrl: String?): List<Int> {
        val explicitPort = explicitSettingsUrl(settingsUrl)?.let { OpenCodeServerUrls.portOf(it) }
        if (explicitPort != null && explicitPort != defaultPort()) {
            // 用户显式指定了非默认端口：只试该端口，失败即报「端口占用」，不静默换端口
            return listOf(explicitPort)
        }
        val preferred = OpenCodeServerUrls.portOf(normalized(env(ENV_SERVER_URL)) ?: normalized(defaultUrl) ?: DEFAULT_SERVER_URL)
            ?: DEFAULT_PORT
        return (listOf(preferred) + fallbackPorts).distinct()
    }

    /** 默认地址的有效端口（默认 4096） */
    fun defaultPort(): Int = OpenCodeServerUrls.portOf(normalized(defaultUrl) ?: DEFAULT_SERVER_URL) ?: DEFAULT_PORT

    /** 用户显式配置且与默认地址不同的地址；空白/非法/等于默认值均视为「未显式配置」 */
    private fun explicitSettingsUrl(settingsUrl: String?): String? =
        normalized(settingsUrl)?.takeIf { it != normalized(defaultUrl) }

    private fun normalized(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() && OpenCodeServerUrls.portOf(it) != null }

    private fun endpoint(
        url: String,
        username: String,
        password: String?,
        source: OpenCodeServerEndpointSource,
    ) = OpenCodeServerEndpoint(
        baseUrl = url,
        username = username,
        password = password,
        port = OpenCodeServerUrls.portOf(url) ?: DEFAULT_PORT,
        source = source,
        owned = false,
    )

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_PORT = 4096
        const val ENV_SERVER_URL = "OPENCODE_SERVER_URL"
        const val FALLBACK_PORT_START = 4097
        const val FALLBACK_PORT_COUNT = 8

        fun defaultFallbackPorts(): List<Int> = (FALLBACK_PORT_START until FALLBACK_PORT_START + FALLBACK_PORT_COUNT).toList()
    }
}
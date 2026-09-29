package com.ayongw.idea.opencode.frontend.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Basic 认证密码存储
 *
 * 密码属敏感信息，不能明文进插件设置文件（IDE 会报 "probably contains sensitive information"），
 * 统一交 IDE 凭据存储（macOS 钥匙串 / Windows 凭据管理器 / Linux 凭据存储）；
 * 同时在内存缓存一份，避免设置页的 isModified 等高频调用反复访问系统凭据服务。
 */
internal object OpenCodePasswordStore {

    private const val SERVICE = "OpenCode"
    private const val ACCOUNT = "opencode"

    private val attributes = CredentialAttributes(generateServiceName(SERVICE, "server"), ACCOUNT)

    @Volatile
    private var cached: String? = null

    /** 读取密码：首次走凭据存储，之后用内存缓存；无凭据服务时按空处理（回退环境变量 / service.json） */
    fun load(): String = cached ?: runCatching {
        PasswordSafe.instance.getPassword(attributes).orEmpty()
    }.getOrDefault("").also { cached = it }

    /** 保存密码：空值表示清除；写入失败不影响本次会话使用 */
    fun save(value: String) {
        cached = value
        runCatching {
            PasswordSafe.instance.set(
                attributes,
                value.takeIf { it.isNotEmpty() }?.let { Credentials(ACCOUNT, it) }
            )
        }
    }
}
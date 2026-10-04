package com.ayongw.idea.opencode.frontend.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import org.jetbrains.annotations.VisibleForTesting
import java.awt.EventQueue

/**
 * Basic 认证密码存储
 *
 * 密码属敏感信息，不能明文进插件设置文件（IDE 会报 "probably contains sensitive information"），
 * 统一交 IDE 凭据存储（macOS 钥匙串 / Windows 凭据管理器 / Linux 凭据存储）；
 * 同时在内存缓存一份：设置页 [com.intellij.openapi.options.Configurable] 的 `isModified` / `reload`
 * 运行在 EDT，而 EDT 上访问 PasswordSafe 会命中 `SlowOperations` 禁令并抛错，
 * 故 EDT 一律只读缓存，缓存由 [preload] 在后台线程提前填充。
 */
object OpenCodePasswordStore {

    private const val SERVICE = "OpenCode"
    private const val ACCOUNT = "opencode"

    private val attributes = CredentialAttributes(generateServiceName(SERVICE, "server"), ACCOUNT)

    @Volatile
    private var cached: String? = null

    /** 线程判定（仅测试替换） */
    @VisibleForTesting
    var isDispatchThread: () -> Boolean = { EventQueue.isDispatchThread() }

    /** 凭据存储读取（仅测试替换）；无凭据服务 / 读取失败按 null 处理 */
    @VisibleForTesting
    var readSafe: () -> String? = {
        runCatching { PasswordSafe.instance.getPassword(attributes) }.getOrNull()
    }

    /** 凭据存储写入（仅测试替换）；null 表示清除 */
    @VisibleForTesting
    var writeSafe: (String?) -> Unit = { value ->
        PasswordSafe.instance.set(attributes, value?.let { Credentials(ACCOUNT, it) })
    }

    /**
     * 读取密码：
     * - 已有缓存：直接返回（EDT / 后台通用）；
     * - EDT 且缓存未命中：**不访问凭据存储**（慢操作被禁止），返回空串，由后台 [preload] 填充；
     * - 后台线程且缓存未命中：同步读取并回填缓存；无凭据时按空处理（回退环境变量 / service.json）。
     */
    fun load(): String {
        cached?.let { return it }
        if (isDispatchThread()) return ""
        return readThrough()
    }

    /**
     * 后台预加载：已缓存直接返回；否则同步读凭据存储填缓存。
     * 由启动 Activity 在后台协程调用，供设置页在 EDT 只读缓存。
     */
    fun preload(): String {
        cached?.let { return it }
        return readThrough()
    }

    /** 保存密码：空串视为清除（下发 null）；写入失败不影响本次会话使用（缓存已更新） */
    fun save(value: String) {
        cached = value
        runCatching { writeSafe(value.takeIf { it.isNotEmpty() }) }
    }

    private fun readThrough(): String =
        readSafe().orEmpty().also { cached = it }

    /** 复位运行态与可替换函数（仅测试使用） */
    @VisibleForTesting
    fun resetForTest() {
        cached = null
        isDispatchThread = { EventQueue.isDispatchThread() }
        readSafe = { runCatching { PasswordSafe.instance.getPassword(attributes) }.getOrNull() }
        writeSafe = { value ->
            PasswordSafe.instance.set(attributes, value?.let { Credentials(ACCOUNT, it) })
        }
    }
}

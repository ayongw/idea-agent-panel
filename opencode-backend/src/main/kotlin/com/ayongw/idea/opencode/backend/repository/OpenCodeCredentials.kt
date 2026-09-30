package com.ayongw.idea.opencode.backend.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.nio.file.Files
import java.nio.file.Path

/**
 * opencode 服务端 Basic 密码发现
 *
 * 顺序与 opencode 自身语义一致：显式传入的值 → `PasswordSafe`（用户手输/设置页保存，重启可复现）→
 * `OPENCODE_SERVER_PASSWORD` → `service.json` 的 `password`。
 * 设置页密码留空时据此兜底，避免「设置页空密码把环境变量 / 文件里的密码覆盖掉」导致 401。
 */
object OpenCodeCredentials {

    fun resolvePassword(explicit: String?): String =
        resolvePassword(explicit, System.getenv(ENV_PASSWORD), readPasswordSafe(), defaultServiceFile())

    /** 可注入环境变量、凭据存储与文件路径的版本，便于单测覆盖各分支 */
    fun resolvePassword(explicit: String?, envPassword: String?, passwordSafe: String?, serviceFile: Path): String {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        passwordSafe?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        envPassword?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return readPassword(serviceFile).orEmpty()
    }

    /** 读取密码文件；文件缺失 / 非法 JSON / 无 `password` 字段均返回 null */
    fun readPassword(file: Path): String? = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching null
        val root = JsonParser.parseString(Files.readString(file))
        (root as? JsonObject)?.asMap()?.get("password")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 从 IDE 凭据存储读取密码（account 与设置页 `OpenCodePasswordStore` 一致，重启后仍可复现）；
     * 无凭据服务 / 未保存时返回 null（继续回退环境变量与 service.json）。
     */
    fun readPasswordSafe(): String? = runCatching {
        PasswordSafe.instance.getPassword(credentialAttributes())
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 保存密码到 IDE 凭据存储（空值表示清除）；写入失败不影响本次会话使用 */
    fun writePasswordSafe(password: String?) {
        val value = password?.trim()
        runCatching {
            PasswordSafe.instance.set(
                credentialAttributes(),
                value?.takeIf { it.isNotEmpty() }?.let { Credentials(PASSWORD_SAFE_ACCOUNT, it) }
            )
        }
    }

    private fun credentialAttributes() =
        CredentialAttributes(generateServiceName(PASSWORD_SAFE_SERVICE, "server"), PASSWORD_SAFE_ACCOUNT)

    /** 后台 service 的密码文件：`~/.config/opencode/service.json` */
    private fun defaultServiceFile(): Path = OpenCodeConfigStore().globalConfigDir().resolve(SERVICE_FILE)

    private const val PASSWORD_SAFE_SERVICE = "OpenCode"
    private const val PASSWORD_SAFE_ACCOUNT = "opencode"
    private const val ENV_PASSWORD = "OPENCODE_SERVER_PASSWORD"
    private const val SERVICE_FILE = "service.json"
}
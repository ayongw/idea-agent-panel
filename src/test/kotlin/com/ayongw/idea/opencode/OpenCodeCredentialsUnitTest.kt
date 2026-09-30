package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.OpenCodeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Basic 密码发现顺序：显式值 → PasswordSafe → `OPENCODE_SERVER_PASSWORD` → `service.json`
 *
 * 回归点：设置页密码留空（`""`）不得把环境变量 / 文件里的密码覆盖成空串（曾导致设置页 401）；
 * 用户手输密钥进 PasswordSafe 后重启仍可复现（历史会话列表不再 401）。
 */
class OpenCodeCredentialsUnitTest {

    private val absent: Path = Files.createTempDirectory("opencode-credentials").resolve("absent.json")

    @Test
    fun explicitPasswordWinsAndIsTrimmed() {
        assertEquals(
            "explicit",
            OpenCodeCredentials.resolvePassword(" explicit ", "env-pwd", "safe-pwd", passwordFile("from-file"))
        )
    }

    @Test
    fun blankExplicitFallsBackToPasswordSafeThenEnvThenFile() {
        assertEquals(
            "safe-pwd",
            OpenCodeCredentials.resolvePassword("", "env-pwd", " safe-pwd ", passwordFile("from-file"))
        )
        assertEquals(
            "env-pwd",
            OpenCodeCredentials.resolvePassword("", " env-pwd ", null, passwordFile("from-file"))
        )
        assertEquals(
            "from-file",
            OpenCodeCredentials.resolvePassword(null, "  ", null, passwordFile("from-file"))
        )
    }

    @Test
    fun noSourceResolvesToEmpty() {
        assertEquals("", OpenCodeCredentials.resolvePassword("", null, null, absent))
    }

    @Test
    fun passwordFileVariants() {
        assertEquals("from-file", OpenCodeCredentials.readPassword(passwordFile("from-file")))
        assertNull(
            "无 password 字段应为 null（Gson 缺键取值曾抛 NPE）",
            OpenCodeCredentials.readPassword(json("""{"port":4096}"""))
        )
        assertNull("非法 JSON 应为 null", OpenCodeCredentials.readPassword(json("{oops")))
        assertNull("文件不存在应为 null", OpenCodeCredentials.readPassword(absent))
    }

    private fun passwordFile(password: String): Path = json("""{"password":"$password"}""")

    private fun json(text: String): Path =
        Files.createTempFile("opencode-credentials", ".json").also { Files.writeString(it, text) }
}
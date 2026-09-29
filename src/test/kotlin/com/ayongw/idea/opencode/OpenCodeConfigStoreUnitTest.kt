package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.ConfigModifiedException
import com.ayongw.idea.opencode.backend.repository.ConfigScope
import com.ayongw.idea.opencode.backend.repository.OpenCodeConfigStore
import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 配置文件定位与定点写入验证（覆盖优先级、缺失文件、并发改动保护）
 */
class OpenCodeConfigStoreUnitTest {

    private lateinit var root: Path
    private lateinit var home: Path
    private lateinit var project: Path
    private var envMap = mapOf<String, String>()

    private fun store() = OpenCodeConfigStore(env = { envMap[it] }, home = home)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("opencode-store-test")
        home = root.resolve("home")
        project = root.resolve("project")
        Files.createDirectories(home)
        Files.createDirectories(project)
    }

    @After
    fun tearDown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun resolvesExistingCandidateInOpencodeOrder() {
        Files.createDirectories(project.resolve(".opencode"))
        Files.writeString(project.resolve(".opencode/opencode.jsonc"), "{}")

        assertEquals(
            project.resolve(".opencode/opencode.jsonc"),
            store().resolveFile(ConfigScope.PROJECT, project)
        )

        Files.writeString(project.resolve("opencode.json"), "{}")
        assertEquals("opencode.json 优先级最高", project.resolve("opencode.json"), store().resolveFile(ConfigScope.PROJECT, project))
    }

    @Test
    fun fallsBackToDefaultFileNamePerScope() {
        assertEquals(project.resolve("opencode.json"), store().resolveFile(ConfigScope.PROJECT, project))
        assertEquals(
            home.resolve(".config/opencode/opencode.jsonc"),
            store().resolveFile(ConfigScope.GLOBAL, project)
        )
    }

    @Test
    fun globalDirFollowsEnvOverrides() {
        envMap = mapOf("OPENCODE_CONFIG_DIR" to "~/custom-opencode")
        assertEquals(home.resolve("custom-opencode"), store().globalConfigDir())

        envMap = mapOf("XDG_CONFIG_HOME" to root.resolve("xdg").toString())
        assertEquals(root.resolve("xdg/opencode"), store().globalConfigDir())
    }

    @Test
    fun projectScopeWithoutDirectoryFails() {
        val error = runCatching { store().resolveFile(ConfigScope.PROJECT, null) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun readsMissingFileAsEmptyObject() {
        val file = project.resolve("opencode.json")

        assertEquals(0, store().readObject(file).size())
    }

    @Test
    fun patchCreatesFileAndRoundTrips() {
        val file = project.resolve("opencode.json")
        val value = JsonArray().apply {
            add("~/.kiro/skills")
        }

        val backup = store().patch(file, listOf("skills"), value)

        assertTrue("新文件写入不应产生备份", backup == null)
        assertTrue(Files.isRegularFile(file))
        val read = store().readObject(file)
        assertEquals(1, read.getAsJsonArray("skills").size())
    }

    @Test
    fun patchKeepsBackupOfPreviousContent() {
        val file = project.resolve("opencode.jsonc")
        Files.writeString(
            file,
            """
            {
              // 保留注释
              "model": "old/provider"
            }
            """.trimIndent()
        )

        val backup = store().patch(
            file,
            listOf("model"),
            com.google.gson.JsonPrimitive("hello-tw/GLM-5.2"),
            expectedText = Files.readString(file)
        )

        assertNotNull(backup)
        assertEquals("备份应保留原内容", Files.readString(backup!!).contains("old/provider"), true)
        val updated = Files.readString(file)
        assertTrue(updated.contains("// 保留注释"))
        assertTrue(updated.contains("hello-tw/GLM-5.2"))
        assertFalse(updated.contains("old/provider"))
    }

    @Test
    fun patchRejectsWriteWhenFileChangedExternally() {
        val file = project.resolve("opencode.json")
        Files.writeString(file, "{\n  \"model\": \"a/b\"\n}")
        val stale = Files.readString(file)
        Files.writeString(file, "{\n  \"model\": \"c/d\"\n}")

        val error = runCatching {
            store().patch(file, listOf("model"), com.google.gson.JsonPrimitive("e/f"), expectedText = stale)
        }.exceptionOrNull()

        assertTrue("并发改动应被拒绝", error is ConfigModifiedException)
        assertTrue("拒绝写入后原文件不变", Files.readString(file).contains("c/d"))
    }

    @Test
    fun patchNoopDoesNotWriteWhenKeyAbsentAndDeleting() {
        val file = project.resolve("opencode.json")
        Files.writeString(file, "{\n  \"model\": \"a/b\"\n}")

        val backup = store().patch(file, listOf("missing"), null)

        assertEquals(null, backup)
        assertEquals(1, store().readObject(file).size())
    }
}
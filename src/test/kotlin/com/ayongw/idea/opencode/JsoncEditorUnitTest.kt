package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.JsoncEditor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * JSONC 定点编辑器验证：写回后注释/缩进/未知字段必须原样保留
 */
class JsoncEditorUnitTest {

    private fun parseJsonc(text: String) =
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.LENIENT
            JsonParser.parseReader(reader)
        }

    private fun localServer(command: List<String>): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "local")
        val arr = JsonArray()
        command.forEach { arr.add(it) }
        obj.add("command", arr)
        return obj
    }

    @Test
    fun insertsIntoEmptyObject() {
        val result = JsoncEditor.patch("{}", listOf("skills"), JsonArray().apply {
            add("~/.kiro/skills")
        })

        assertEquals(
            """
            {
              "skills": [
                "~/.kiro/skills"
              ]
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun appendsSiblingToNonEmptyObject() {
        val input = """
            {
              "a": 1
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("b"), JsonPrimitive("x"))

        assertEquals(
            """
            {
              "a": 1,
              "b": "x"
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun insertsNestedValueKeepingCommentsAndUnknownFields() {
        val input = """
            {
              // 说明注释
              "mcp": {
                "servers": {}
              },
              "disabled_providers": [],
              "unknown_field": true
            }
        """.trimIndent()

        val result = JsoncEditor.patch(
            input,
            listOf("mcp", "servers", "demo"),
            localServer(listOf("npx", "-y", "demo-mcp"))
        )

        assertTrue("注释必须保留", result.contains("// 说明注释"))
        assertTrue("未知字段必须保留", result.contains("\"unknown_field\": true"))
        assertTrue("插入的键应位于 servers 内", result.contains("\"demo\": {"))

        val root = parseJsonc(result).asJsonObject
        val demo = root.getAsJsonObject("mcp").getAsJsonObject("servers").getAsJsonObject("demo")
        assertEquals("local", demo.get("type").asString)
        assertEquals(3, demo.getAsJsonArray("command").size())
        assertTrue(root.has("disabled_providers"))
    }

    @Test
    fun createsMissingIntermediateObjects() {
        val input = """
            {
              "providers": {}
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("providers", "local", "name"), JsonPrimitive("本地"))

        assertEquals(
            """
            {
              "providers": {
                "local": {
                  "name": "本地"
                }
              }
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun createsTopLevelKeyWhenMissing() {
        val result = JsoncEditor.patch("{\n  \"a\": 1\n}\n", listOf("model"), JsonPrimitive("hello-tw/GLM-5.2"))

        val root = parseJsonc(result).asJsonObject
        assertEquals("hello-tw/GLM-5.2", root.get("model").asString)
        assertEquals(1, root.get("a").asInt)
    }

    @Test
    fun replacesScalarInPlace() {
        val input = """
            {
              // 注释保留
              "providers": {
                "local": {
                  "settings": { "baseURL": "http://old" }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(
            input,
            listOf("providers", "local", "settings", "baseURL"),
            JsonPrimitive("http://new")
        )

        assertTrue(result.contains("// 注释保留"))
        assertTrue(result.contains("\"baseURL\": \"http://new\""))
    }

    @Test
    fun replacesArrayValue() {
        val input = """
            {
              "skills": [
                "old"
              ]
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("skills"), JsonArray().apply {
            add("a")
            add("b")
        })

        assertEquals(
            """
            {
              "skills": [
                "a",
                "b"
              ]
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun deletesMiddleMemberWithoutDanglingComma() {
        val input = """
            {
              "a": 1,
              "b": 2,
              "c": 3
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("b"), null)

        assertEquals(
            """
            {
              "a": 1,
              "c": 3
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun deletesLastMemberRemovingPreviousComma() {
        val input = """
            {
              "a": 1,
              "b": 2,
              "c": 3
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("c"), null)

        assertEquals(
            """
            {
              "a": 1,
              "b": 2
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun deletesOnlyMemberLeavingEmptyObject() {
        val result = JsoncEditor.patch("{\n  \"a\": 1\n}\n", listOf("a"), null)

        assertEquals("{\n}\n", result)
        assertEquals(0, parseJsonc(result).asJsonObject.size())
    }

    @Test
    fun deletesNestedKeyKeepingSiblings() {
        val input = """
            {
              "mcp": {
                "servers": {
                  "keep": { "type": "remote", "url": "https://a" },
                  "drop": { "type": "local", "command": ["x"] }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("mcp", "servers", "drop"), null)

        val servers = parseJsonc(result).asJsonObject
            .getAsJsonObject("mcp").getAsJsonObject("servers")
        assertEquals(1, servers.size())
        assertTrue(servers.has("keep"))
    }

    @Test
    fun minifiedInputStaysCompactAfterScalarReplace() {
        val result = JsoncEditor.patch("{\"a\":1}", listOf("a"), JsonPrimitive(2))

        assertEquals("{\"a\":2}", result)
    }

    @Test
    fun deletesKeyThatIsAbsentWithoutChangingText() {
        val input = "{\n  \"a\": 1\n}\n"

        val result = JsoncEditor.patch(input, listOf("missing"), null)

        assertEquals(input, result)
    }

    @Test
    fun rejectsNonObjectRoot() {
        val error = runCatching { JsoncEditor.patch("[1]", listOf("a"), JsonPrimitive(1)) }.exceptionOrNull()

        assertTrue("非对象根节点应被拒绝", error is IllegalArgumentException)
    }

    // ==================== 模型页的按键写入（禁用/启用/增删） ====================

    @Test
    fun disablesModelKeepingSiblingFields() {
        val input = """
            {
              "providers": {
                "hello-tw": {
                  "models": {
                    "GLM-5.2": {
                      "name": "GLM 5.2",
                      "limit": { "context": 128000 }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(
            input,
            listOf("providers", "hello-tw", "models", "GLM-5.2", "disabled"),
            JsonPrimitive(true)
        )

        val model = parseJsonc(result).asJsonObject
            .getAsJsonObject("providers").getAsJsonObject("hello-tw")
            .getAsJsonObject("models").getAsJsonObject("GLM-5.2")
        assertTrue(model.get("disabled").asBoolean)
        assertEquals("GLM 5.2", model.get("name").asString)
        assertEquals(128000, model.getAsJsonObject("limit").get("context").asInt)
    }

    @Test
    fun enablingModelRemovesDisabledKeyOnly() {
        val input = """
            {
              "providers": {
                "hello-tw": {
                  "models": {
                    "GLM-5.2": { "name": "GLM 5.2", "disabled": true }
                  }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(
            input,
            listOf("providers", "hello-tw", "models", "GLM-5.2", "disabled"),
            null
        )

        val model = parseJsonc(result).asJsonObject
            .getAsJsonObject("providers").getAsJsonObject("hello-tw")
            .getAsJsonObject("models").getAsJsonObject("GLM-5.2")
        assertTrue("disabled 键应被删除", !model.has("disabled"))
        assertEquals("GLM 5.2", model.get("name").asString)
    }

    @Test
    fun addingModelCreatesEntryAndKeepsExistingOnes() {
        val input = """
            {
              "providers": {
                "hello-tw": {
                  "models": {
                    "existing": {}
                  }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(
            input,
            listOf("providers", "hello-tw", "models", "custom-1", "name"),
            JsonPrimitive("自定义模型")
        )

        val models = parseJsonc(result).asJsonObject
            .getAsJsonObject("providers").getAsJsonObject("hello-tw")
            .getAsJsonObject("models")
        assertEquals(2, models.size())
        assertEquals("自定义模型", models.getAsJsonObject("custom-1").get("name").asString)
        assertTrue(models.has("existing"))
    }

    @Test
    fun removingModelDeletesEntryKeepingSiblings() {
        val input = """
            {
              "providers": {
                "hello-tw": {
                  "models": {
                    "keep": { "name": "保留" },
                    "drop": { "name": "删除" }
                  }
                }
              }
            }
        """.trimIndent()

        val result = JsoncEditor.patch(input, listOf("providers", "hello-tw", "models", "drop"), null)

        val models = parseJsonc(result).asJsonObject
            .getAsJsonObject("providers").getAsJsonObject("hello-tw")
            .getAsJsonObject("models")
        assertEquals(1, models.size())
        assertTrue(models.has("keep"))
    }
}
package com.ayongw.idea.opencode.backend.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** 配置作用域 */
enum class ConfigScope {
    /** 全局：`~/.config/opencode`（可被 OPENCODE_CONFIG_DIR / XDG_CONFIG_HOME 覆盖） */
    GLOBAL,

    /** 项目：IDE 当前项目根目录 */
    PROJECT
}

/** 配置文件在读取后被外部改动 */
class ConfigModifiedException(val file: Path) :
    IllegalStateException("配置文件已被外部修改，请重新加载：$file")

/**
 * opencode 配置文件（opencode.json / opencode.jsonc）读写
 *
 * - 定位规则与 opencode 自身一致：同一目录内 `opencode.jsonc` 优先级高于 `opencode.json`
 *   （`Config.loadDirectory` 按 `["opencode.json","opencode.jsonc"]` 顺序加载，后者覆盖前者），
 *   候选顺序为 `opencode.jsonc` → `opencode.json` → `.opencode/opencode.jsonc` → `.opencode/opencode.json`，
 *   都不存在时返回默认新建路径 `opencode.jsonc`（与 `Config.update` 一致）
 * - 写入走 [JsoncEditor] 定点 patch，保留注释与缩进；写前备份 `.bak`，写后原子落盘
 * - 支持传入「读取时的文本」做并发校验，发现外部改动直接拒绝写入
 */
class OpenCodeConfigStore(
    private val env: (String) -> String? = { System.getenv(it) },
    private val home: Path = Paths.get(System.getProperty("user.home"))
) {

    companion object {
        /** 候选文件（相对作用域目录），按优先级由高到低 */
        val CANDIDATES = listOf(
            "opencode.jsonc",
            "opencode.json",
            ".opencode/opencode.jsonc",
            ".opencode/opencode.json"
        )

        /** 默认新建的文件名（与 opencode core `Config.update` 一致，不区分作用域） */
        const val DEFAULT_FILE = "opencode.jsonc"
    }

    /** 全局配置目录：OPENCODE_CONFIG_DIR → XDG_CONFIG_HOME/opencode → ~/.config/opencode */
    fun globalConfigDir(): Path {
        env("OPENCODE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { return expandUserPath(it) }
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it).resolve("opencode") }
        return home.resolve(".config").resolve("opencode")
    }

    /** 作用域目录 */
    fun scopeDir(scope: ConfigScope, projectDir: Path?): Path = when (scope) {
        ConfigScope.GLOBAL -> globalConfigDir()
        ConfigScope.PROJECT -> projectDir ?: throw IllegalArgumentException("项目级作用域需要项目目录")
    }

    /** 定位目标配置文件：已存在的候选中优先级最高者，否则返回默认新建路径 */
    fun resolveFile(scope: ConfigScope, projectDir: Path?): Path {
        val base = scopeDir(scope, projectDir)
        CANDIDATES.firstOrNull { Files.isRegularFile(base.resolve(it)) }?.let { return base.resolve(it) }
        return base.resolve(DEFAULT_FILE)
    }

    /** 读取文本，文件不存在返回 null */
    fun readText(file: Path): String? =
        if (Files.isRegularFile(file)) Files.readString(file) else null

    /** 读取配置（JSONC 宽松解析，允许注释与尾逗号），文件不存在返回空对象 */
    fun readObject(file: Path): JsonObject {
        val text = readText(file)?.takeIf { it.isNotBlank() } ?: return JsonObject()
        val element = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.LENIENT
            JsonParser.parseReader(reader)
        }
        return element as? JsonObject ?: JsonObject()
    }

    /**
     * 定点写入（[value] 为 null 表示删除键）
     *
     * @param expectedText 读取时的文本；非 null 时会校验文件未被外部改动
     * @return 备份文件路径；内容无变化时返回 null
     */
    fun patch(file: Path, path: List<String>, value: JsonElement?, expectedText: String? = null): Path? {
        val original = readText(file)
        if (expectedText != null && expectedText != original) {
            throw ConfigModifiedException(file)
        }
        val base = original?.takeIf { it.isNotBlank() } ?: "{}\n"
        val updated = JsoncEditor.patch(base, path, value)
        // 内容无变化（含「删除不存在的键」）时不落盘
        if (updated == (original ?: base)) return null
        val backup = original?.let { backup(file, it) }
        writeAtomically(file, updated)
        return backup
    }

    /** 备份原文件到 `<file>.bak` */
    fun backup(file: Path, content: String): Path {
        val target = file.resolveSibling(file.fileName.toString() + ".bak")
        Files.createDirectories(file.parent)
        Files.writeString(target, content)
        return target
    }

    /** 原子写（先写同目录临时文件，再 move 覆盖） */
    fun writeAtomically(file: Path, content: String) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".opencode-", ".tmp")
        try {
            Files.writeString(tmp, content)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 展开路径开头的 `~` */
    fun expandUserPath(path: String): Path {
        val trimmed = path.trim()
        return when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> home.resolve(trimmed.removePrefix("~/"))
            else -> Paths.get(trimmed)
        }
    }
}
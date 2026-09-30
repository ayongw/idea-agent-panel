package com.ayongw.idea.opencode.backend.server

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 一个引用者（= 某个 IDE 进程中的一次 project 使用，`referenceId = "<ideProcessId>:<projectHash>"`）
 *
 * [heartbeatAt] 用于崩溃残留判定：超过阈值未刷新的引用者视为「崩溃未 release」而被剔除。
 */
data class OpenCodeServerReference(val referenceId: String, val heartbeatAt: Long)

/**
 * 注册表中的一条自有实例记录（TSD-31 §3.3 / §3.7）
 *
 * 「自有」的强判据是**本插件拉起 + pid 匹配**；本记录只登记自有实例，[refCount] 即引用者数量。
 */
data class OpenCodeServerRegistryEntry(
    val port: Int,
    val pid: Long,
    val baseUrl: String,
    val startedAt: Long,
    val pluginVersion: String,
    val references: List<OpenCodeServerReference> = emptyList(),
) {
    val refCount: Int get() = references.size
}

/** [OpenCodeServerRegistry.release] 的归属判定结果 */
sealed interface OpenCodeServerReleaseOutcome {
    /** 引用计数归零且引用列表为空：调用方即最后一个引用者，负责优雅终止进程并删除条目（条目已从注册表移除） */
    data class LastReleaser(val entry: OpenCodeServerRegistryEntry) : OpenCodeServerReleaseOutcome

    /** 仍有其他引用者：只递减，不终止进程（返回移除本引用后的最新条目） */
    data class StillReferenced(val entry: OpenCodeServerRegistryEntry) : OpenCodeServerReleaseOutcome

    /** 注册表中无此端口 */
    data object NotRegistered : OpenCodeServerReleaseOutcome
}

/** 陈旧/崩溃残留清理报告（TSD-31 §3.7） */
data class OpenCodeServerRegistryCleanup(
    /** 因 pid 已不存在而整体删除的端口 */
    val removedEntries: List<Int>,
    /** 因心跳过期而被剔除的引用者数量 */
    val removedStaleReferences: Int,
    /** 引用计数为 0 但进程仍存活的端口（崩溃残留，交给调用方决定接管或终止） */
    val orphanedPorts: List<Int>,
)

/**
 * OpenCode Server 共享注册表（TSD-31 §3.3 / §3.7）
 *
 * 本机所有 IDE 实例（多工作区/多窗口）共享同一份 **文件** 注册表，决定「自有实例何时被终止」：
 *
 * - **文件即唯一事实来源**：每次操作都从 [storeFile] 重新读取、改、写回，不维护长期内存态；
 * - **原子写**：先写同目录临时文件再 `ATOMIC_MOVE` 覆盖，避免读到半截内容；
 * - **跨进程互斥 + JVM 内串行**：`FileLock` 在**同一 JVM 内**重叠会抛 `OverlappingFileLockException`，
 *   故先用私有监视器串行化同 JVM 并发，再用兄弟锁文件做跨进程互斥；
 * - **降级路径**（§12 A12 / R13）：拿不到锁或平台不支持时**直接执行业务逻辑**（不依赖锁），
 *   以幂等写 + 心跳收敛替代强互斥，降级不抛异常、不静默丢数据；
 * - 本类**不依赖**同包其他类型（状态/端点/端口分配/输出缓冲），可独立单测。
 */
class OpenCodeServerRegistry(
    private val storeFile: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val heartbeatStaleMs: Long = DEFAULT_HEARTBEAT_STALE_MS,
) {

    private val gson = Gson()

    /** JVM 内串行化监视器：必须在取文件锁之前持有（避免同 JVM 内 FileLock 重叠） */
    private val jvmMonitor = Any()

    private val absoluteStore: Path = storeFile.toAbsolutePath().normalize()

    /** 临时文件与锁文件都落在注册表同目录，保证 `ATOMIC_MOVE` 不跨文件系统 */
    private val storeDir: Path = absoluteStore.parent ?: Path.of(".")

    private val lockFile: Path =
        storeDir.resolve(absoluteStore.fileName.toString().removeSuffix(JSON_SUFFIX) + LOCK_SUFFIX)

    /** 读取全部条目（文件缺失/为空/非法 JSON 一律视为空注册表，不抛异常） */
    fun load(): List<OpenCodeServerRegistryEntry> = synchronized(jvmMonitor) { readEntries() }

    fun findByPort(port: Int): OpenCodeServerRegistryEntry? =
        synchronized(jvmMonitor) { readEntries().firstOrNull { it.port == port } }

    /** 注册/覆盖某端口的自有实例（新注册会把 references 重置为空），返回写入后的条目 */
    fun registerOwned(port: Int, pid: Long, baseUrl: String, pluginVersion: String): OpenCodeServerRegistryEntry =
        withExclusive {
            val entries = readEntries().filterNot { it.port == port }.toMutableList()
            val entry = OpenCodeServerRegistryEntry(
                port = port,
                pid = pid,
                baseUrl = baseUrl,
                startedAt = clock(),
                pluginVersion = pluginVersion,
                references = emptyList(),
            )
            entries += entry
            writeEntries(entries)
            entry
        }

    /** 引用计数 +1；条目不存在返回 null；同一 [referenceId] 重复 acquire 幂等（只刷新心跳，不重复计数） */
    fun acquire(port: Int, referenceId: String): OpenCodeServerRegistryEntry? = withExclusive {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.port == port }
        if (index < 0) return@withExclusive null

        val entry = entries[index]
        val now = clock()
        val references = entry.references.toMutableList()
        val existing = references.indexOfFirst { it.referenceId == referenceId }
        if (existing >= 0) {
            references[existing] = references[existing].copy(heartbeatAt = now)
        } else {
            references += OpenCodeServerReference(referenceId, now)
        }

        val updated = entry.copy(references = references)
        entries[index] = updated
        writeEntries(entries)
        updated
    }

    /**
     * 引用计数 -1 并返回归属判定结果
     *
     * 移除 [referenceId] 后若引用列表为空 → [OpenCodeServerReleaseOutcome.LastReleaser]（并从文件中删除该条目）；
     * 否则 → [OpenCodeServerReleaseOutcome.StillReferenced]；条目不存在 → [OpenCodeServerReleaseOutcome.NotRegistered]。
     * 全过程在同一把锁内完成，保证并发 release 只产生一次 [OpenCodeServerReleaseOutcome.LastReleaser]。
     */
    fun release(port: Int, referenceId: String): OpenCodeServerReleaseOutcome = withExclusive {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.port == port }
        if (index < 0) return@withExclusive OpenCodeServerReleaseOutcome.NotRegistered

        val entry = entries[index]
        val remaining = entry.references.filterNot { it.referenceId == referenceId }
        if (remaining.isEmpty()) {
            entries.removeAt(index)
            writeEntries(entries)
            OpenCodeServerReleaseOutcome.LastReleaser(entry)
        } else {
            val updated = entry.copy(references = remaining)
            entries[index] = updated
            writeEntries(entries)
            OpenCodeServerReleaseOutcome.StillReferenced(updated)
        }
    }

    /** 刷新某引用者心跳；条目或引用者不存在返回 false */
    fun heartbeat(port: Int, referenceId: String): Boolean = withExclusive {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.port == port }
        if (index < 0) return@withExclusive false

        val entry = entries[index]
        val references = entry.references.toMutableList()
        val target = references.indexOfFirst { it.referenceId == referenceId }
        if (target < 0) return@withExclusive false

        references[target] = references[target].copy(heartbeatAt = clock())
        entries[index] = entry.copy(references = references)
        writeEntries(entries)
        true
    }

    fun remove(port: Int) = withExclusive {
        writeEntries(readEntries().filterNot { it.port == port })
    }

    fun clear() = withExclusive {
        writeEntries(emptyList())
    }

    /**
     * 清理陈旧条目与过期引用（TSD-31 §3.7）
     *
     * - [isProcessAlive] 返回 false 的条目整体删除（陈旧条目）；
     * - 存活条目中 `clock() - heartbeatAt > heartbeatStaleMs` 的引用者被剔除；
     * - 清理后引用列表为空但进程仍存活 → 记入 [OpenCodeServerRegistryCleanup.orphanedPorts]（**不**自动删除条目）。
     *
     * [isProcessAlive] 由调用方注入（生产用 PID 存活探测；单测可注入假实现）。
     */
    fun cleanupStale(isProcessAlive: (Long) -> Boolean): OpenCodeServerRegistryCleanup = withExclusive {
        val now = clock()
        val removedEntries = ArrayList<Int>()
        val orphanedPorts = ArrayList<Int>()
        var removedStaleReferences = 0
        val kept = ArrayList<OpenCodeServerRegistryEntry>()

        readEntries().forEach { entry ->
            if (!isProcessAlive(entry.pid)) {
                removedEntries += entry.port
                return@forEach
            }

            val fresh = entry.references.filter { now - it.heartbeatAt <= heartbeatStaleMs }
            removedStaleReferences += entry.references.size - fresh.size
            if (fresh.isEmpty()) orphanedPorts += entry.port
            kept += entry.copy(references = fresh)
        }

        writeEntries(kept)
        OpenCodeServerRegistryCleanup(
            removedEntries = removedEntries,
            removedStaleReferences = removedStaleReferences,
            orphanedPorts = orphanedPorts,
        )
    }

    /**
     * 把读-改-写整体包进「JVM 内串行 + 跨进程文件锁」
     *
     * 拿不到锁（被占用 / 平台不支持 / 抛异常）时降级为无锁执行，靠幂等写 + 心跳收敛（§12 A12 / R13）。
     */
    private fun <T> withExclusive(block: () -> T): T = synchronized(jvmMonitor) {
        val handle = tryAcquireFileLock()
        try {
            block()
        } finally {
            handle?.close()
        }
    }

    /** 尝试取跨进程文件锁；返回 null 表示不可用（调用方直接执行，不依赖锁） */
    private fun tryAcquireFileLock(): LockHandle? {
        var channel: FileChannel? = null
        return try {
            Files.createDirectories(storeDir)
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                channel = null
                null
            } else {
                val handle = LockHandle(channel, lock)
                channel = null
                handle
            }
        } catch (_: Exception) {
            runCatching { channel?.close() }
            null
        }
    }

    /** 读取注册表文件并规整为空安全的条目列表 */
    private fun readEntries(): List<OpenCodeServerRegistryEntry> {
        val text = runCatching {
            if (Files.exists(absoluteStore)) Files.readString(absoluteStore, StandardCharsets.UTF_8) else ""
        }.getOrDefault("")
        if (text.isBlank()) return emptyList()

        return runCatching {
            val file = gson.fromJson(text, StoredRegistryFile::class.java)
            (file?.entries ?: emptyList()).mapNotNull { it?.toEntry() }
        }.getOrDefault(emptyList())
    }

    /** 原子写：临时文件 + ATOMIC_MOVE 覆盖，目标不存在时也能创建 */
    private fun writeEntries(entries: List<OpenCodeServerRegistryEntry>) {
        Files.createDirectories(storeDir)
        val json = gson.toJson(StoredRegistryFile(SCHEMA_VERSION, entries.map { it.toStored() }))
        val tmp = Files.createTempFile(storeDir, absoluteStore.fileName.toString(), TMP_SUFFIX)
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8)
            moveAtomically(tmp, absoluteStore)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun OpenCodeServerRegistryEntry.toStored(): StoredEntry = StoredEntry(
        port = port,
        pid = pid,
        baseUrl = baseUrl,
        startedAt = startedAt,
        pluginVersion = pluginVersion,
        references = references.map { StoredReference(it.referenceId, it.heartbeatAt) },
    )

    private fun StoredEntry.toEntry(): OpenCodeServerRegistryEntry = OpenCodeServerRegistryEntry(
        port = port,
        pid = pid,
        baseUrl = baseUrl ?: "",
        startedAt = startedAt,
        pluginVersion = pluginVersion ?: "",
        references = (references ?: emptyList()).mapNotNull { ref ->
            ref?.referenceId?.let { OpenCodeServerReference(it, ref.heartbeatAt) }
        },
    )

    /**
     * 文件锁句柄：关闭顺序必须「先释放锁、再关 channel」，且关闭异常不得吞掉业务结果
     */
    private class LockHandle(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) {
        fun close() {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    /**
     * 落盘用 DTO：字段全部可空，避免 Gson 反序列化缺键时把 Kotlin 非空字段置 null 引发 NPE
     */
    private data class StoredRegistryFile(
        val version: Int = SCHEMA_VERSION,
        val entries: List<StoredEntry?>? = null,
    )

    private data class StoredEntry(
        val port: Int = 0,
        val pid: Long = 0,
        val baseUrl: String? = null,
        val startedAt: Long = 0,
        val pluginVersion: String? = null,
        val references: List<StoredReference?>? = null,
    )

    private data class StoredReference(
        val referenceId: String? = null,
        val heartbeatAt: Long = 0,
    )

    companion object {
        /** 引用者心跳过期阈值（建议 ≥ 5 分钟，见 §3.7） */
        const val DEFAULT_HEARTBEAT_STALE_MS: Long = 5 * 60 * 1000L

        private const val SCHEMA_VERSION = 1
        private const val JSON_SUFFIX = ".json"
        private const val LOCK_SUFFIX = ".lock"
        private const val TMP_SUFFIX = ".tmp"
        private const val REGISTRY_DIR_NAME = "opencode-idea-panel"
        private const val REGISTRY_FILE_NAME = "server-registry.json"

        /** 生产用注册表路径：`<IDE 系统目录>/opencode-idea-panel/server-registry.json` */
        fun defaultStoreFile(): Path = Path.of(PathManager.getSystemPath(), REGISTRY_DIR_NAME, REGISTRY_FILE_NAME)
    }
}
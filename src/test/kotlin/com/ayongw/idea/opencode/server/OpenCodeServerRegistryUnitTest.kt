package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.backend.server.OpenCodeServerReference
import com.ayongw.idea.opencode.backend.server.OpenCodeServerRegistry
import com.ayongw.idea.opencode.backend.server.OpenCodeServerReleaseOutcome
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 共享注册表与引用计数（TSD-31 §3.3 / §3.7）：文件即事实源、原子写、幂等 acquire、
 * 「最后一个引用者」判定、跨线程串行、陈旧/崩溃残留清理。
 *
 * 全部用例在隔离临时目录内运行，时间与 pid 存活均由注入的假实现控制。
 */
class OpenCodeServerRegistryUnitTest {

    private lateinit var dir: Path

    /** 可控假时钟：用例内推进以模拟心跳老化 */
    private var now: Long = 1_000L

    private val baseUrl = "http://127.0.0.1:4096"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("opencode-registry")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun registry(
        file: Path = dir.resolve("server-registry.json"),
        staleMs: Long = OpenCodeServerRegistry.DEFAULT_HEARTBEAT_STALE_MS,
    ) = OpenCodeServerRegistry(file, clock = { now }, heartbeatStaleMs = staleMs)

    private fun json(text: String): JsonObject =
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.LENIENT
            JsonParser.parseReader(reader).asJsonObject
        }

    @Test
    fun `同端口重复注册会覆盖旧条目并重置引用列表`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.acquire(4096, "ref-a")
        assertEquals(1, registry.findByPort(4096)!!.refCount)

        val overwritten = registry.registerOwned(4096, 222L, baseUrl, "1.1.0")

        assertEquals("重新注册即新实例，引用清零", 0, overwritten.refCount)
        assertEquals(222L, overwritten.pid)
        assertEquals("1.1.0", overwritten.pluginVersion)
        assertEquals("同端口只保留一条", 1, registry.load().size)
    }

    @Test
    fun `acquire 增加引用且同 referenceId 重复 acquire 幂等`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")

        val first = registry.acquire(4096, "ref-a")!!
        assertEquals(1, first.refCount)
        assertEquals(1_000L, first.references.single().heartbeatAt)

        now = 5_000L
        val second = registry.acquire(4096, "ref-a")!!

        assertEquals("重复 acquire 不重复计数", 1, second.refCount)
        assertEquals("只刷新心跳", 5_000L, second.references.single().heartbeatAt)
    }

    @Test
    fun `对不存在端口 acquire 返回 null`() {
        assertNull(registry().acquire(4096, "ref-a"))
    }

    @Test
    fun `两个引用者关一个不停关最后一个才判定为 LastReleaser`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.acquire(4096, "ref-a")
        registry.acquire(4096, "ref-b")

        val first = registry.release(4096, "ref-a")

        assertTrue(first is OpenCodeServerReleaseOutcome.StillReferenced)
        assertEquals(1, (first as OpenCodeServerReleaseOutcome.StillReferenced).entry.refCount)
        assertNotNull("仍有引用者，条目保留", registry.findByPort(4096))

        val last = registry.release(4096, "ref-b")

        assertTrue("归零即最后一个引用者", last is OpenCodeServerReleaseOutcome.LastReleaser)
        val lastEntry = (last as OpenCodeServerReleaseOutcome.LastReleaser).entry
        assertEquals("LastReleaser 携带被移除前的条目", 1, lastEntry.refCount)
        assertEquals(listOf("ref-b"), lastEntry.references.map { it.referenceId })
        assertNull("条目已从注册表删除", registry.findByPort(4096))
        assertTrue(registry.load().isEmpty())
    }

    @Test
    fun `并发 release 只产生一次 LastReleaser`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        val ids = (0 until 8).map { "ref-$it" }
        ids.forEach { registry.acquire(4096, it) }

        val outcomes = Collections.synchronizedList(mutableListOf<OpenCodeServerReleaseOutcome>())
        val pool = Executors.newFixedThreadPool(ids.size)
        val start = CountDownLatch(1)
        ids.forEach { id ->
            pool.submit {
                start.await()
                outcomes += registry.release(4096, id)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("并发 release 应在超时内完成", pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(1, outcomes.count { it is OpenCodeServerReleaseOutcome.LastReleaser })
        assertEquals(7, outcomes.count { it is OpenCodeServerReleaseOutcome.StillReferenced })
        assertNull(registry.findByPort(4096))
    }

    @Test
    fun `并发 acquire 不同引用者后引用计数等于引用者数`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        val count = 16

        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        repeat(count) { i ->
            pool.submit {
                start.await()
                registry.acquire(4096, "ref-$i")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val entry = registry.findByPort(4096)!!
        assertEquals(count, entry.refCount)
        assertEquals("引用者不重复", count, entry.references.map { it.referenceId }.distinct().size)
    }

    @Test
    fun `cleanup 删除 pid 已不存在的条目`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.registerOwned(4097, 222L, baseUrl, "1.0.0")

        val report = registry.cleanupStale { pid -> pid == 222L }

        assertEquals(listOf(4096), report.removedEntries)
        assertNull("陈旧条目被整体删除", registry.findByPort(4096))
        assertNotNull("存活进程的条目保留", registry.findByPort(4097))
    }

    @Test
    fun `cleanup 剔除心跳过期的引用并重算引用计数`() {
        val registry = registry(staleMs = 1_000L)
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.acquire(4096, "fresh")

        now = 2_100L
        registry.acquire(4096, "stale")

        now = 3_000L // fresh 心跳 1000（过期），stale 心跳 2100（仍新鲜）
        val report = registry.cleanupStale { true }

        assertEquals(1, report.removedStaleReferences)
        assertEquals("重算后计数小于原计数", 1, registry.findByPort(4096)!!.refCount)
        assertEquals("过期引用者被剔除", listOf("stale"), registry.findByPort(4096)!!.references.map { it.referenceId })
        assertTrue("仍有引用者，不算孤儿", report.orphanedPorts.isEmpty())
    }

    @Test
    fun `cleanup 对引用归零但进程存活的端口记入 orphanedPorts 且保留条目`() {
        val registry = registry(staleMs = 1_000L)
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.acquire(4096, "gone")

        now = 5_000L // 心跳 1000，已远超阈值
        val report = registry.cleanupStale { true }

        assertEquals(1, report.removedStaleReferences)
        assertEquals(listOf(4096), report.orphanedPorts)
        assertNotNull("孤儿端口不自动删除，交调用方处置", registry.findByPort(4096))
        assertEquals(0, registry.findByPort(4096)!!.refCount)
        assertTrue(report.removedEntries.isEmpty())
    }

    @Test
    fun `对不存在端口 release 返回 NotRegistered`() {
        val registry = registry()
        assertEquals(OpenCodeServerReleaseOutcome.NotRegistered, registry.release(4096, "ref-a"))

        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        assertEquals(OpenCodeServerReleaseOutcome.NotRegistered, registry.release(5099, "ref-a"))
    }

    @Test
    fun `heartbeat 对不存在的条目或引用返回 false`() {
        val registry = registry()
        assertFalse("端口不存在", registry.heartbeat(4096, "ref-a"))

        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        assertFalse("引用者不存在", registry.heartbeat(4096, "ref-a"))

        registry.acquire(4096, "ref-b")
        assertTrue("引用者存在时刷新成功", registry.heartbeat(4096, "ref-b"))
    }

    @Test
    fun `文件缺失为空或内容非法时 load 返回空列表且不抛异常`() {
        val missing = registry(file = dir.resolve("nested/server-registry.json"))
        assertTrue("父目录不存在也不抛异常", missing.load().isEmpty())

        val emptyFile = dir.resolve("empty.json")
        Files.writeString(emptyFile, "")
        assertTrue(registry(file = emptyFile).load().isEmpty())

        val blankFile = dir.resolve("blank.json")
        Files.writeString(blankFile, "   \n")
        assertTrue(registry(file = blankFile).load().isEmpty())

        val invalidFile = dir.resolve("invalid.json")
        Files.writeString(invalidFile, "{ this is not json")
        assertTrue("非法 JSON 视为空注册表", registry(file = invalidFile).load().isEmpty())

        val wrongShape = dir.resolve("wrong-shape.json")
        Files.writeString(wrongShape, "[]")
        assertTrue("非对象结构也容错", registry(file = wrongShape).load().isEmpty())
    }

    @Test
    fun `写入后文件是合法 JSON 且能被重新加载往返一致`() {
        val storeFile = dir.resolve("server-registry.json")
        val registry = registry(file = storeFile)
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.acquire(4096, "ref-a")

        val text = Files.readString(storeFile, StandardCharsets.UTF_8)
        val root = json(text)
        assertEquals("schema 版本", 1, root["version"].asInt)
        assertTrue("entries 为数组", root["entries"].isJsonArray)

        val reloaded = registry(file = storeFile).load()
        assertEquals(1, reloaded.size)
        val entry = reloaded.single()
        assertEquals(4096, entry.port)
        assertEquals(111L, entry.pid)
        assertEquals(baseUrl, entry.baseUrl)
        assertEquals(1_000L, entry.startedAt)
        assertEquals("1.0.0", entry.pluginVersion)
        assertEquals(listOf(OpenCodeServerReference("ref-a", 1_000L)), entry.references)
        assertEquals(1, entry.refCount)
    }

    @Test
    fun `clear 后注册表为空且文件仍可读`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.registerOwned(4097, 222L, baseUrl, "1.0.0")

        registry.clear()

        assertTrue(registry.load().isEmpty())
        assertNull(registry.findByPort(4096))
        assertNull(registry.findByPort(4097))
    }

    @Test
    fun `remove 只删除指定端口`() {
        val registry = registry()
        registry.registerOwned(4096, 111L, baseUrl, "1.0.0")
        registry.registerOwned(4097, 222L, baseUrl, "1.0.0")

        registry.remove(4096)

        assertNull(registry.findByPort(4096))
        assertNotNull("其他端口不受影响", registry.findByPort(4097))
    }
}
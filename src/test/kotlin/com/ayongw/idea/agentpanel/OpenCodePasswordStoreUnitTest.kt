package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.frontend.settings.OpenCodePasswordStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码缓存的线程语义：EDT 只读缓存（绝不访问 PasswordSafe），后台线程可读穿填缓存；
 * preload 幂等；save 更新缓存并下发写入。
 *
 * 回归点：设置页 reload 在 EDT 上访问 PasswordSafe 命中 SlowOperations 禁令，
 * 密码读空后 Test Connection 回退到旧密码而失败。
 */
class OpenCodePasswordStoreUnitTest {

    private var readCount = 0
    private val writes = mutableListOf<String?>()

    private fun installStubs(edt: Boolean) {
        readCount = 0
        writes.clear()
        OpenCodePasswordStore.resetForTest()
        OpenCodePasswordStore.isDispatchThread = { edt }
        OpenCodePasswordStore.readSafe = {
            readCount++
            "stored-pwd"
        }
        OpenCodePasswordStore.writeSafe = { value -> writes.add(value) }
    }

    @After
    fun tearDown() {
        OpenCodePasswordStore.resetForTest()
    }

    @Test
    fun edtCacheMissReturnsEmptyAndDoesNotReadSafe() {
        installStubs(edt = true)
        assertEquals("", OpenCodePasswordStore.load())
        assertEquals(0, readCount)
    }

    @Test
    fun edtReturnsCachedWithoutReadingSafe() {
        installStubs(edt = true)
        OpenCodePasswordStore.save("new-pwd")
        readCount = 0
        writes.clear()
        assertEquals("new-pwd", OpenCodePasswordStore.load())
        assertEquals(0, readCount)
    }

    @Test
    fun backgroundThreadReadsThroughAndCaches() {
        installStubs(edt = false)
        assertEquals("stored-pwd", OpenCodePasswordStore.load())
        assertEquals(1, readCount)
        // 再次读取命中缓存
        assertEquals("stored-pwd", OpenCodePasswordStore.load())
        assertEquals(1, readCount)
    }

    @Test
    fun backgroundNullSafeYieldsEmptyAndIsCached() {
        OpenCodePasswordStore.resetForTest()
        OpenCodePasswordStore.isDispatchThread = { false }
        OpenCodePasswordStore.readSafe = { readCount++; null }
        assertEquals("", OpenCodePasswordStore.preload())
        assertEquals(1, readCount)
        assertEquals("", OpenCodePasswordStore.load())
        assertEquals(1, readCount)
    }

    @Test
    fun preloadIsIdempotent() {
        installStubs(edt = false)
        repeat(3) { OpenCodePasswordStore.preload() }
        assertEquals("stored-pwd", OpenCodePasswordStore.preload())
        assertEquals(1, readCount)
    }

    @Test
    fun preloadThenEdtLoadGetsCachedPassword() {
        installStubs(edt = false)
        assertEquals("stored-pwd", OpenCodePasswordStore.preload())
        OpenCodePasswordStore.isDispatchThread = { true }
        assertEquals("stored-pwd", OpenCodePasswordStore.load())
        assertEquals(1, readCount)
    }

    @Test
    fun saveUpdatesCacheAndWritesThrough() {
        installStubs(edt = false)
        OpenCodePasswordStore.save("fresh-pwd")
        assertEquals(listOf("fresh-pwd"), writes)
        assertEquals("fresh-pwd", OpenCodePasswordStore.preload())
        assertEquals("save 后不得再读存储", 0, readCount)
    }

    @Test
    fun saveBlankWritesNullToClear() {
        installStubs(edt = false)
        OpenCodePasswordStore.save("")
        assertEquals(listOf<String?>(null), writes)
        assertEquals("", OpenCodePasswordStore.load())
    }
}

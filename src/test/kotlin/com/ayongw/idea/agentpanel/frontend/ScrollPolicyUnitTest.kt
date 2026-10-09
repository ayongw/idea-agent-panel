package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.ScrollPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘底滚动判定（TSD-30 §5.2）：仅在底部阈值内贴底，上翻期间不抢滚动
 */
class ScrollPolicyUnitTest {

    private val policy = ScrollPolicy(threshold = 24)

    @Test
    fun `在底部阈值内判定为贴底`() {
        // 视口高度 800，内容 2000：viewPos=1200 时 viewPos+extent=2000 == 底部
        assertTrue(policy.shouldStickToBottom(viewPositionY = 1200, extentHeight = 800, viewSizeHeight = 2000))
        // 差 20 像素，仍在阈值内
        assertTrue(policy.shouldStickToBottom(viewPositionY = 1180, extentHeight = 800, viewSizeHeight = 2000))
    }

    @Test
    fun `离开底部超过阈值不贴底`() {
        // 差 100 像素，超过阈值 24
        assertFalse(policy.shouldStickToBottom(viewPositionY = 1100, extentHeight = 800, viewSizeHeight = 2000))
        // 完全在顶部
        assertFalse(policy.shouldStickToBottom(viewPositionY = 0, extentHeight = 800, viewSizeHeight = 2000))
    }

    @Test
    fun `内容不满一屏时判定为贴底`() {
        // 内容 500 少于视口 800：视口已到底（无滚动空间），应贴底
        assertTrue(policy.shouldStickToBottom(viewPositionY = 0, extentHeight = 800, viewSizeHeight = 500))
    }

    @Test
    fun `阈值可注入`() {
        val strict = ScrollPolicy(threshold = 4)
        // 差 10 像素：默认阈值 24 会贴底，严格阈值 4 不贴底
        assertTrue(policy.shouldStickToBottom(790, 800, 1600))
        assertFalse(strict.shouldStickToBottom(790, 800, 1600))
    }
}

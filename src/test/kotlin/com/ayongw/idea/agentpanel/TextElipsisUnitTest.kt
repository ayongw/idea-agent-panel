package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.TextElipsis
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 窄窗口下模型名省略的纯函数契约。
 *
 * 宽度测量以假 [TextElipsis.elide] 的 `measure` 注入（每字符 10px，省略号 10px），
 * 不依赖 FontMetrics，覆盖放得下 / 需要截断 / 极端窄 三类边界。
 */
class TextElipsisUnitTest {

    /** 每字符 10px 的假测量（中文按 1 字符计，与生产按像素测量的口径差异不影响本测试意图） */
    private val measure: (String) -> Int = { it.length * 10 }

    @Test
    fun 放得下则原样返回() {
        assertEquals("MiMo", TextElipsis.elide("MiMo", 40, measure))
    }

    @Test
    fun 空文本原样返回() {
        assertEquals("", TextElipsis.elide("", 10, measure))
    }

    @Test
    fun 宽度非正视为不限() {
        assertEquals("MiMo-V2.6-Flash Free", TextElipsis.elide("MiMo-V2.6-Flash Free", 0, measure))
        assertEquals("MiMo-V2.6-Flash Free", TextElipsis.elide("MiMo-V2.6-Flash Free", -5, measure))
    }

    @Test
    fun 超宽则尾部省略且不超限() {
        // 21 字符 × 10px = 210；上限 80px 只放得下 8 个字符位（7 字符 + 省略号）
        val result = TextElipsis.elide("MiMo-V2.6-Flash Free", 80, measure)

        assertEquals("MiMo-V2…", result)
        assertEquals("省略后宽度不应超过上限", 80, measure(result))
    }

    @Test
    fun 恰好等于上限不省略() {
        // 21 字符 × 10px = 210，正好等于上限
        val text = "MiMo-V2.6-Flash Free"
        assertEquals(text, TextElipsis.elide(text, 210, measure))
    }

    @Test
    fun 省略号都放不下时返回空串() {
        // 上限 5px < 省略号 10px：连省略号都摆不下，宁可留空也不能溢出
        assertEquals("", TextElipsis.elide("MiMo", 5, measure))
    }

    @Test
    fun 只能放下省略号时返回省略号() {
        assertEquals("…", TextElipsis.elide("MiMo", 10, measure))
    }

    @Test
    fun 只放得下省略号时窄文本退化为省略号() {
        // 每字符 20px：文本 40px > 上限 25px；省略号 20px 放得下，但「1 字符 + 省略号」放不下
        val cjkMeasure: (String) -> Int = { it.length * 20 }
        assertEquals("…", TextElipsis.elide("模型", 25, cjkMeasure))
    }

    @Test
    fun 中文模型名同样按宽度省略() {
        // 每字符 20px：4 字 = 80px > 上限 70px → 只放得下「2 字 + 省略号」= 60px
        val cjkMeasure: (String) -> Int = { it.length * 20 }
        assertEquals("通义…", TextElipsis.elide("通义千问", 70, cjkMeasure))
    }
}

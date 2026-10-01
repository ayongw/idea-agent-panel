package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.LayoutCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

/**
 * 几何缺失/滞后判定（TSD-30 §5.1 纯逻辑）：
 * 任一内容子组件 `width == 0`（从未布局）或 `height != preferredSize.height`
 * （内容更新后未重布局）即视为 stale，需同步布局。
 */
class LayoutCoordinatorUnitTest {

    private val coordinator = LayoutCoordinator(container = JPanel())

    @Test
    fun `从未布局的子组件判定为缺失`() {
        // JPanel 默认 bounds 为 0x0（未布局）
        assertTrue(coordinator.hasStaleGeometry(listOf(JPanel())))
    }

    @Test
    fun `宽度为0的子组件判定为缺失`() {
        val child = JPanel().apply { setSize(0, 100) }
        assertTrue(coordinator.hasStaleGeometry(listOf(child)))
    }

    @Test
    fun `高度滞后于preferredSize判定为滞后`() {
        val child = JPanel().apply {
            preferredSize = java.awt.Dimension(100, 50)
            // 内容更新后 bounds 仍停在旧高度（模拟流式更新后未重布局）
            setSize(100, 30)
        }
        assertTrue(coordinator.hasStaleGeometry(listOf(child)))
    }

    @Test
    fun `尺寸与preferred一致时判定为正常`() {
        val child = JPanel().apply {
            preferredSize = java.awt.Dimension(100, 50)
            setSize(100, 50)
        }
        assertFalse(coordinator.hasStaleGeometry(listOf(child)))
    }

    @Test
    fun `空集合不判定为缺失`() {
        assertFalse(coordinator.hasStaleGeometry(emptyList()))
    }

    @Test
    fun `占位组件不参与判定时不算缺失`() {
        // filler 类粘性占位：高度随剩余空间伸缩，preferred 高为 0（常态 height != preferred）
        val filler = javax.swing.Box.createVerticalGlue().apply { setSize(552, 300) }
        val bubble = JPanel().apply {
            preferredSize = java.awt.Dimension(552, 80)
            setSize(552, 80)
        }
        // 只把气泡交给判定（filler 由调用方排除，否则永远误报 stale）
        assertFalse(coordinator.hasStaleGeometry(listOf(bubble)))
    }
}

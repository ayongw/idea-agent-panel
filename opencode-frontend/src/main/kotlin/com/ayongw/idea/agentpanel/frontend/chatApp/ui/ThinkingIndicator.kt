package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.animation.Animation
import com.intellij.util.animation.Easing
import com.intellij.util.animation.JBAnimator
import com.intellij.util.ui.JBUI
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class ThinkingIndicator : JPanel(), Disposable {
    private val animator = JBAnimator(this).apply {
        isCyclic = true
        period = ChatUIConstants.ThinkingIndicator.ANIMATION_PERIOD_MS
    }

    private val dots = List(ChatUIConstants.ThinkingIndicator.DOT_COUNT) { PulsingDot() }

    init {
        setupAppearance()
        dots.forEach { add(it) }
    }

    private fun setupAppearance() {
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatUIConstants.ThinkingIndicator.PADDING)
    }

    override fun addNotify() {
        super.addNotify()
        start()
    }

    override fun removeNotify() {
        animator.stop()
        super.removeNotify()
    }

    override fun dispose() {
        // 必须走 Disposer.dispose 而非 animator.stop()：
        // JBAnimator(Disposable) 构造即 Disposer.register(parent, this)，而本类自身没有注册进
        // Disposer 树，ObjectTree.getParentNode 对未注册对象返回 ROOT 节点 —— animator 因此直接
        // 挂在 ROOT_DISPOSABLE 下。stop() 只停动画、不摘树节点，于是每建一个指示器就在 ROOT 上
        // 永久留一个节点（IDE 退出时报 ThinkingIndicator 内存泄漏）。
        // Disposer.dispose 幂等，dismissIndicator 与气泡 dispose 重复调用安全。
        Disposer.dispose(animator)
    }

    private fun start() {
        val cfg = ChatUIConstants.ThinkingIndicator
        val animations = dots.mapIndexed { index, dot ->
            Animation { fraction ->
                dot.alpha = cfg.MIN_ALPHA + (cfg.MAX_ALPHA - cfg.MIN_ALPHA) * pingPong(fraction)
                dot.repaint()
            }.apply {
                delay = index * cfg.STAGGER_MS
                duration = cfg.CYCLE_MS
                easing = Easing.LINEAR
            }
        }
        animator.animate(animations)
    }

    private fun pingPong(fraction: Double): Double =
        if (fraction <= 0.5) fraction * 2.0 else (1.0 - fraction) * 2.0

    private class PulsingDot : JPanel() {
        var alpha: Double = ChatUIConstants.ThinkingIndicator.MIN_ALPHA
            set(value) {
                field = value.coerceIn(0.0, 1.0)
            }

        init {
            isOpaque = false
            val size = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_DIAMETER)
            val spacing = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_SPACING)
            preferredSize = Dimension(size + spacing, size)
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat())
                g2d.color = ChatAppColors.Text.normal
                val size = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_DIAMETER)
                val y = (height - size) / 2
                g2d.fillOval(0, y, size, size)
            } finally {
                g2d.dispose()
            }
        }
    }
}
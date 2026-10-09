package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.diagnostic.Logger
import java.awt.Component
import java.awt.Container
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * 布局协调器（TSD-30 §5.1）：布局单一入口 + 几何自愈兜底。
 *
 * L3 渲染协调层组件：收敛 [ChatList] 分散的 `revalidate/doLayout/repaint` 调用，
 * 业务代码只允许经 [requestLayout] 触发布局（契约 C-布局）。
 *
 * 背景（实测结论，JDK 21 源码 + 日志实证，见 TSD-30 §7 / TSD-07 §3.4）：
 * `Container.validate()` 的条件是 `!isValid() && peer != null`——轻量组件（scroll pane
 * 里的 JPanel）`peer == null`，所以 `validate()` 是空操作；`revalidate()` 的延迟校验
 * 在该链路里同样不保证落到 `layoutContainer`。结果是视口按 `preferredSize` 给容器
 * `setSize`（滚动条正常），但气泡 `bounds` 恒为 `0x0`，一个都画不出来。
 *
 * 因此这里不依赖 Swing 的延迟校验机制：revalidate 之后，仅在「几何缺失/滞后」时
 * 直接同步跑布局（[forceLayout]），作为调试期自愈兜底——这是技术债，命中时以 DEBUG
 * 记录，一旦 §5.7 布局断言测试确认根因消除即按 TSD-30 §7 删除。
 */
class LayoutCoordinator(
    private val container: Container,
    private val log: Logger = Logger.getInstance(LayoutCoordinator::class.java),
) {
    /**
     * 布局单一入口：revalidate + 几何自愈兜底 + repaint 收敛到一处。
     *
     * 动态增删子组件后只允许走这里（C-布局）。[contentChildren] 参与几何缺失判定
     * （见 [ensureLaidOut]），filler 等粘性占位组件需排除。
     */
    fun requestLayout(contentChildren: Collection<Component>) {
        container.revalidate()
        ensureLaidOut(contentChildren)
        container.repaint()
    }

    /**
     * 保证容器子树已完成布局。
     *
     * [contentChildren] 是参与几何判定的内容子组件（filler 等占位组件除外：
     * 粘性占位的高度随剩余空间伸缩，`height != preferredSize` 是常态，若参与
     * 判定会每次都误报 stale，导致兜底永远触发）。
     *
     * 判定条件（几何缺失或滞后才同步布局，而非无条件 doLayout）：
     * - 首次消息：视口 validate 尚未执行、容器高度为 0，气泡被布局进 0 高区域不可见
     *   （日志实证 `size=552x0` → thinking 到达后 `552x104`）；
     * - 流式更新：气泡 `preferredSize` 已变而 `bounds` 保持旧值，内容被截断 / 整屏空白
     *   （resize 触发全量 validate 才恢复）。
     *
     * 同步布局前先把容器高度对齐 preferredSize（宽度由视口负责，见方法内注释）：
     * 视口（`tracksViewportHeight=false`）按内容 preferred 设高，但首次消息时视口
     * validate 未执行（容器高度仍为 0），若直接用 0 高度 doLayout 会把气泡布局到
     * 0 高度内。
     */
    fun ensureLaidOut(contentChildren: Collection<Component>) {
        if (!SwingUtilities.isEventDispatchThread() || container.width <= 0) return
        var stale = hasStaleGeometry(contentChildren)
        if (!stale && container.isValid) return
        alignHeightAndForceLayout()
        // 恒定再收敛一趟（不依赖 stale 门）：首趟给气泡 setSize 后，其高度可能恰好
        // 等于 BoxLayout 按旧宽度缓存的 preferred，hasStaleGeometry 识别不出，而真实
        // 高度要等换行 View 同步后才显现。正常文本只多一次廉价遍历，保证长文首帧
        // 高度即正确，不依赖 paint 链。
        // 第二趟前置：失效布局管理器缓存（headless 下 invalidate 传播短路），并把组件
        // 宽度同步给换行 View（生产中由 paint 触发；headless/面板不可见时没有 paint）
        syncWrappedTextAreas(container)
        alignHeightAndForceLayout()
        log.debug(
            "ensureLaidOut: forced stale=$stale valid=${container.isValid} " +
                "children=${container.componentCount} " +
                "size=${container.size.width}x${container.size.height}"
        )
    }

    /** 高度对齐 preferredSize（宽度由视口负责）后递归强制布局 */
    private fun alignHeightAndForceLayout() {
        val pref = container.preferredSize
        if (container.height != pref.height) {
            // 只对齐高度：宽度由视口负责（MessagesContainer 声明 tracksViewportWidth=true）。
            // 若连宽度一起 setSize(preferredSize)，超宽子组件（长单行文本的未换行 preferred
            // 宽度）会把容器强行撑到视口数倍宽（实测 658 字思考行 → 容器 2199px，视口仅
            // ~365px），与视口宽度追踪互相拉扯、几何永不收敛，滚动定位落空 → 整屏空白。
            container.setSize(container.width, pref.height)
        }
        forceLayout()
    }

    /**
     * 几何缺失/滞后判定（纯逻辑，可单测）：任一内容子组件 `width == 0`（从未布局）
     * 或 `height != preferredSize.height`（内容更新后未重布局）即为 stale。
     */
    fun hasStaleGeometry(contentChildren: Collection<Component>): Boolean =
        contentChildren.any { it.width == 0 || it.height != it.preferredSize.height }

    /**
     * 第二趟布局前置：失效子树布局缓存 + 同步换行文本宽度。
     *
     * 1. 直接调每个容器布局管理器的 `invalidateLayout`。headless/未 validate 的子树
     *    `valid` 恒 false，`Container.invalidate()` 会直接 return、不向布局管理器传播，
     *    BoxLayout 首趟缓存的错误高度清不掉；这里绕开 valid 短路，模拟真实 invalidate
     *    传播（无缓存的 GridBagLayout 等调用无害）。
     * 2. 对开启换行（lineWrap）且已分到宽度的 JTextArea 读一次 preferredSize：
     *    BasicTextUI.getPreferredSize（JDK 21 源码实证）在组件宽高非 0 时把组件宽度
     *    同步给根 View → WrappedPlainView.setSize 检测宽度变化 → 按新宽 breakLines 重算
     *    行数。正常窗口该链路由 paint 驱动，此处保证 headless / 面板不可见同样收敛
     *    （用户原则：超宽先换行，永不因超宽隐藏）。
     *
     * 代码块（CodeBlockPane）的文本区 lineWrap=false，不匹配——代码不折行，靠自带
     * 横向滚动条查看。
     */
    private fun syncWrappedTextAreas(container: Container) {
        (container.layout as? java.awt.LayoutManager2)?.invalidateLayout(container)
        container.components.forEach { child ->
            if (child is JTextArea && child.lineWrap && child.width > 0) {
                child.preferredSize
            }
            if (child is Container && child.isVisible) syncWrappedTextAreas(child)
        }
    }

    /** 递归强制布局（`doLayout` 直接调布局管理器，绕开 isValid / RepaintManager 的
     * 延迟校验）。调试期自愈兜底，仅由 [ensureLaidOut] 的 stale 判定触发。
     */
    fun forceLayout() = forceLayout(container)

    private fun forceLayout(container: Container) {
        container.doLayout()
        container.components.forEach { child ->
            if (child is Container && child.isVisible) forceLayout(child)
        }
    }
}

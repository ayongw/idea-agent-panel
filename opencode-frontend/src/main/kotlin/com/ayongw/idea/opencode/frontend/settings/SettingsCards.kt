package com.ayongw.idea.opencode.frontend.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 设置页的卡片列表：一行一张卡片（标题 + 可选多行副标题 + 右侧操作区），支持关键词过滤。
 *
 * 「两行文本 + 行内按钮」用表格表达不了（表格单元格不能换行），故改用轻量卡片；
 * 列表宽度跟随设置对话框（列容器实现 [Scrollable]，横向铺满视口）。
 */
internal class SettingsCardList(filterLabel: String) : JPanel(BorderLayout()) {

    private val searchField = JBTextField(SEARCH_COLUMNS)
    private val column = CardsColumn()
    private var cards: List<SettingsCard> = emptyList()

    init {
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JBLabel(filterLabel))
                add(searchField)
            },
            BorderLayout.NORTH
        )
        add(JBScrollPane(column).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            // 高度收口，卡片在列表内部滚动（与表格一致），避免整页被长列表撑高
            preferredSize = Dimension(JBUI.scale(CARDS_WIDTH), JBUI.scale(CARDS_HEIGHT))
        }, BorderLayout.CENTER)
    }

    /** 替换卡片内容（过滤条件保持不变） */
    fun setCards(newCards: List<SettingsCard>) {
        cards = newCards
        applyFilter()
    }

    private fun applyFilter() {
        val query = searchField.text.trim()
        column.removeAll()
        cards.filter { it.matches(query) }.forEachIndexed { index, card ->
            if (index > 0) column.add(separator())
            column.add(card)
        }
        column.revalidate()
        column.repaint()
    }

    private fun separator(): JComponent = JSeparator().apply {
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        alignmentX = LEFT_ALIGNMENT
    }

    /** 纵向列容器：横向跟随视口宽度，卡片因此能拿到实际可用宽度 */
    private class CardsColumn : JPanel(), Scrollable {

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(64)

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val SEARCH_COLUMNS = 24

        /** 卡片区 preferred 尺寸（逻辑像素）：宽度与设置页内容宽度一致，高度收口后在内部滚动 */
        const val CARDS_WIDTH = 540
        const val CARDS_HEIGHT = 260
    }
}

/**
 * 一张卡片：左侧标题（粗体）+ 可选多行副标题，右侧操作区；可选「可展开」在标题行下方追加一块内容区。
 *
 * 副标题用固定宽度的 HTML 标签实现折行——设置页内容宽度本身已收口，无需按窗口动态重排。
 */
internal class SettingsCard(title: String, subtitle: String? = null) : JPanel(BorderLayout()) {

    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
    private val text = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val header = JPanel(BorderLayout()).apply { isOpaque = false }
    private val titleText = title
    private val subtitleText = subtitle.orEmpty()

    /** 展开箭头：仅在 [withExpandable] 后可见 */
    private val arrow = JBLabel(AllIcons.General.ArrowRight).apply {
        isVisible = false
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty(2, 0, 0, 4)
    }

    private var body: JComponent? = null
    private var onExpand: (() -> Unit)? = null
    private var expanded = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 6)

        text.add(
            JBLabel(titleText).apply {
                font = JBUI.Fonts.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            }
        )
        if (subtitleText.isNotBlank()) {
            text.add(
                JBLabel(wrappedHtml(subtitleText)).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = UIUtil.getContextHelpForeground()
                    verticalAlignment = SwingConstants.TOP
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        }

        header.add(arrow, BorderLayout.WEST)
        header.add(text, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
    }

    /** 追加右侧操作组件（按钮 / 开关等） */
    fun withAction(component: JComponent): SettingsCard {
        actions.add(component)
        return this
    }

    /**
     * 让卡片可展开：点击标题行切换展开态，[content] 显示在标题行下方（整体缩进对齐副标题）。
     * [onExpand] 在每次展开时回调——按需加载交给调用方，卡片只负责显示与折叠。
     */
    fun withExpandable(content: JComponent, onExpand: () -> Unit): SettingsCard {
        arrow.isVisible = true
        this.onExpand = onExpand
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(EXPAND_INDENT)
            add(content, BorderLayout.CENTER)
            isVisible = false
        }
        body = contentPanel
        add(contentPanel, BorderLayout.CENTER)
        installToggle(arrow)
        installToggle(text)
        return this
    }

    /** 切换展开态；每次由收起变为展开时触发一次 [onExpand] */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        arrow.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        body?.isVisible = value
        if (value) onExpand?.invoke()
        revalidate()
        repaint()
    }

    /** 关键词过滤：命中标题或副标题（忽略大小写） */
    fun matches(query: String): Boolean =
        query.isBlank() ||
            titleText.contains(query, ignoreCase = true) ||
            subtitleText.contains(query, ignoreCase = true)

    /** 标题区（不含右侧操作按钮）可点击切换展开：手型光标 + 左键回调 */
    private fun installToggle(component: JComponent) {
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) setExpanded(!expanded)
            }
        })
        component.components.forEach { child -> if (child is JComponent) installToggle(child) }
    }
}

/** 折行 HTML（固定宽度 + 转义 + 换行转 `<br>`） */
private fun wrappedHtml(text: String): String =
    "<html><body style='width:${JBUI.scale(SUBTITLE_WIDTH)}px'>${htmlEscape(text)}</body></html>"

internal fun wrappedHintHtml(text: String, suffix: String? = null, width: Int = HINT_WIDTH): String {
    val tail = suffix?.let { " <span style='color:gray'>（${htmlEscape(it)}）</span>" }.orEmpty()
    return "<html><body style='width:${JBUI.scale(width)}px'>${htmlEscape(text)}$tail</body></html>"
}

/**
 * 小号上下文字体的折行标签：固定折行宽度，避免长路径把设置页撑宽。
 *
 * @param suffix 括号里的补充说明（如来源类型），传 null 时不显示
 */
internal fun wrappedHintLabel(text: String, suffix: String? = null, width: Int = HINT_WIDTH): JBLabel =
    JBLabel(wrappedHintHtml(text, suffix, width)).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

/** 取路径的父目录（纯字符串处理，不访问文件系统） */
internal fun parentDirOf(path: String): String =
    path.trimEnd('/', '\\').substringBeforeLast('/', "")

/** 转义 HTML 特殊字符，并把换行转成 `<br>` */
internal fun htmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\r\n", "<br>")
    .replace("\n", "<br>")

/** 截断文本到 [limit] 个字符，超出补省略号 */
internal fun truncate(text: String, limit: Int): String =
    if (text.length <= limit) text else text.take(limit) + "…"

/** 取路径的文件名（兼容 `/` 分隔，忽略结尾分隔符） */
internal fun fileNameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

/** 副标题折行宽度（逻辑像素），与设置页内容宽度收口一致 */
private const val SUBTITLE_WIDTH = 400

/** 说明行折行宽度（逻辑像素） */
private const val HINT_WIDTH = 430

/** 卡片展开内容相对标题的缩进（逻辑像素） */
private const val EXPAND_INDENT = 12
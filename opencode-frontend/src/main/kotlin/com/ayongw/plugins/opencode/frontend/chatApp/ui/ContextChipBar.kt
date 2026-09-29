package com.ayongw.plugins.opencode.frontend.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.plugins.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.plugins.opencode.shared.ContextFile
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder

/**
 * 上下文标签栏 - 显示当前文件、选区、显式添加的文件
 */
class ContextChipBar : JPanel() {

    private val chips = mutableMapOf<String, Chip>()

    init {
        setupAppearance()
    }

    private fun setupAppearance() {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(
            ChatUIConstants.Spacing.SMALL,
            ChatUIConstants.Spacing.NORMAL
        )
    }

    /**
     * 设置上下文文件列表
     */
    fun setContextFiles(contextFiles: List<ContextFile>) {
        removeAll()

        // 显示当前文件和选区的特殊标签
        val explicitFiles = contextFiles.filter { it.isExplicit }
        val autoFiles = contextFiles.filter { !it.isExplicit }

        if (autoFiles.isNotEmpty()) {
            autoFiles.forEach { file ->
                addChip(file.name, ChipType.AUTO_FILE, file.path) {
                    removeContextFile(file.path)
                }
            }
        }

        if (explicitFiles.isNotEmpty()) {
            if (autoFiles.isNotEmpty()) addSeparator()
            explicitFiles.forEach { file ->
                addChip(file.name, ChipType.EXPLICIT_FILE, file.path) {
                    removeContextFile(file.path)
                }
            }
        }

        // 如果没有上下文，显示占位提示
        if (contextFiles.isEmpty()) {
            val placeholder = JBLabel(OpencodeFrontendBundle.message("chat.context.empty")).apply {
                foreground = ChatAppColors.Text.disabled
                font = JBFont.small()
            }
            add(placeholder)
        }

        add(Box.createHorizontalGlue())
        revalidate()
        repaint()
    }

    private fun addSeparator() {
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
        val separator = JPanel().apply {
            preferredSize = Dimension(1, JBUI.scale(16))
            maximumSize = Dimension(1, JBUI.scale(16))
            background = ChatAppColors.Text.disabled
        }
        add(separator)
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
    }

    private fun addChip(text: String, type: ChipType, id: String, onRemove: () -> Unit) {
        val chip = Chip(text, type, onRemove)
        chips[id] = chip
        add(chip)
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
    }

    private fun removeContextFile(path: String) {
        chips.remove(path)
        // 触发外部回调
        onContextFileRemoved?.invoke(path)
    }

    var onContextFileRemoved: ((String) -> Unit)? = null

    sealed class ChipType(val color: Color) {
        object AUTO_FILE : ChipType(ChatAppColors.Context.autoFile)
        object EXPLICIT_FILE : ChipType(ChatAppColors.Context.explicitFile)
    }

    private class Chip(
        text: String,
        type: ChipType,
        onRemove: () -> Unit
    ) : JPanel() {

        private val chipText: String = text

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(2, 8, 2, 4),
                EmptyBorder(2, 8, 2, 8)
            )
            background = type.color
            putClientProperty("JComponent.roundRect", true)

            val label = JBLabel(chipText).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Context.onContextChip
            }
            add(label)

            val removeBtn = JButton().apply {
                icon = ChatAppIcons.Context.remove
                preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusable = false
                addActionListener { onRemove() }
                ButtonUtils.applyHoverEffect(this)
            }
            add(removeBtn)
        }
    }
}
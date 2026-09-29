package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.RuleFileDto
import com.ayongw.idea.opencode.shared.SettingsSnapshotDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 规则面板
 *
 * 上：规则加载位置（`AGENTS.md` 所在目录 + 配置 `instructions` 条目）
 * 下：已加载规则文件卡片（文件名 + 文件前 150 个字符），右侧齿轮在编辑器中打开该文件
 *
 * 规则内容不在设置页内编辑——opencode 只从 `AGENTS.md` 读取规则。
 */
internal class RuleSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.rules")

    private val locationsRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val loadedLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val cards = SettingsCardList(OpencodeFrontendBundle.message("settings.opencode.filter"))

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val locationsBlock = JPanel(BorderLayout()).apply {
            add(
                JBLabel(OpencodeFrontendBundle.message("settings.opencode.rules.locations")).apply {
                    font = JBUI.Fonts.label().asBold()
                },
                BorderLayout.NORTH
            )
            add(buildScroll(locationsRow, 90), BorderLayout.CENTER)
        }

        val loadedBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(
                        JBLabel(OpencodeFrontendBundle.message("settings.opencode.rules.list")).apply {
                            font = JBUI.Fonts.label().asBold()
                        }
                    )
                    add(loadedLabel)
                },
                BorderLayout.NORTH
            )
            add(cards, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(
                buildHeader(title, OpencodeFrontendBundle.message("settings.opencode.rules.hint")) { reload() },
                BorderLayout.NORTH
            )
            add(
                JPanel(BorderLayout()).apply {
                    add(locationsBlock, BorderLayout.NORTH)
                    add(loadedBlock, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            renderLocations(snapshot)
            // 只列出已存在的规则文件；不存在的候选在位置上已说明扫描目录
            val existing = snapshot.ruleFiles.filter { it.exists }
            cards.setCards(existing.map { ruleCard(it) })
            loadedLabel.text = OpencodeFrontendBundle.message("settings.opencode.count", existing.size)
        }
    }

    private fun renderLocations(snapshot: SettingsSnapshotDto) {
        locationsRow.removeAll()
        snapshot.ruleFiles.forEach { file ->
            locationsRow.add(
                locationLabel(parentDirOf(file.path), OpencodeFrontendBundle.message("settings.opencode.rules.agents.md"))
            )
        }
        snapshot.instructions.forEach { entry ->
            locationsRow.add(
                locationLabel(entry, OpencodeFrontendBundle.message("settings.opencode.rules.instructions"))
            )
        }
        if (locationsRow.componentCount == 0) {
            locationsRow.add(buildHint(OpencodeFrontendBundle.message("settings.opencode.rules.locations.empty")))
        }
        locationsRow.revalidate()
        locationsRow.repaint()
    }

    private fun locationLabel(path: String, kind: String): JComponent = wrappedHintLabel("• " + path, kind)

    private fun ruleCard(file: RuleFileDto): SettingsCard {
        val scope = OpencodeFrontendBundle.message(
            if (file.scope == ConfigScopeDto.GLOBAL) "settings.opencode.scope.short.global"
            else "settings.opencode.scope.short.project"
        )
        val preview = file.preview?.takeIf { it.isNotBlank() }
            ?: OpencodeFrontendBundle.message("settings.opencode.rules.empty.file")
        return SettingsCard(
            title = fileNameOf(file.path) + "（" + scope + "）",
            subtitle = truncate(preview, PREVIEW_CHARS)
        ).withAction(
            buildGearButton(OpencodeFrontendBundle.message("settings.opencode.rules.open.file")) {
                openInEditor(file.path)
            }
        )
    }

    override fun isModified(): Boolean = false

    private companion object {
        /** 文件预览展示的字符数（后端已截断，这里再兜一层） */
        const val PREVIEW_CHARS = 150
    }
}
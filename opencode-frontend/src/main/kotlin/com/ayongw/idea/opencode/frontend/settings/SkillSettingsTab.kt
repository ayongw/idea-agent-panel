package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.SkillDto
import com.ayongw.idea.opencode.shared.SkillSourceDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 技能面板
 *
 * 上：技能加载来源（只读）——配置 `skills` 声明的路径/URL + opencode 约定扫描目录；编辑直接打开配置文件
 * 下：已加载技能列表（第一行 `id`、第二行描述），右侧齿轮跳转到该技能所在目录
 */
internal class SkillSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.skills")

    private val sourcesRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val loadedLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val cards = SettingsCardList(OpencodeFrontendBundle.message("settings.opencode.filter"))

    /** 全局配置文件路径（快照给出，可能尚不存在，打开时后端会自动建出） */
    private var globalConfigPath: String = ""

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val sourcesBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JBLabel(OpencodeFrontendBundle.message("settings.opencode.skills.sources")).apply {
                            font = JBUI.Fonts.label().asBold()
                        },
                        BorderLayout.WEST
                    )
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                            add(
                                JButton(OpencodeFrontendBundle.message("settings.opencode.config.open")).apply {
                                    addActionListener { openGlobalConfigFile() }
                                }
                            )
                        },
                        BorderLayout.EAST
                    )
                },
                BorderLayout.NORTH
            )
            add(buildScroll(sourcesRow, 90), BorderLayout.CENTER)
        }

        val loadedBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(
                        JBLabel(OpencodeFrontendBundle.message("settings.opencode.skills.list")).apply {
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
                buildHeader(title, OpencodeFrontendBundle.message("settings.opencode.skills.hint")) { reload() },
                BorderLayout.NORTH
            )
            add(
                JPanel(BorderLayout()).apply {
                    add(sourcesBlock, BorderLayout.NORTH)
                    add(loadedBlock, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            globalConfigPath = snapshot.globalConfigPath
            renderSources(snapshot.skillSources)

            cards.setCards(snapshot.discoveredSkills.map { skillCard(it) })
            loadedLabel.text = OpencodeFrontendBundle.message(
                "settings.opencode.count",
                snapshot.discoveredSkills.size
            )
        }
    }

    private fun renderSources(sources: List<SkillSourceDto>) {
        sourcesRow.removeAll()
        if (sources.isEmpty()) {
            sourcesRow.add(buildHint(OpencodeFrontendBundle.message("settings.opencode.skills.sources.empty")))
        } else {
            sources.forEach { sourcesRow.add(sourceLabel(it)) }
        }
        sourcesRow.revalidate()
        sourcesRow.repaint()
    }

    private fun sourceLabel(source: SkillSourceDto): JComponent {
        val kind = OpencodeFrontendBundle.message(
            if (source.declared) "settings.opencode.skills.source.declared"
            else "settings.opencode.skills.source.convention"
        )
        val suffix = if (source.exists) kind
        else kind + " · " + OpencodeFrontendBundle.message("settings.opencode.skills.source.missing")
        return wrappedHintLabel("• " + source.path, suffix)
    }

    private fun skillCard(skill: SkillDto): SettingsCard {
        val description = skill.description?.takeIf { it.isNotBlank() }
            ?: OpencodeFrontendBundle.message("settings.opencode.skills.no.description")
        return SettingsCard(title = skill.id, subtitle = description).withAction(
            buildGearButton(OpencodeFrontendBundle.message("settings.opencode.skills.open.dir")) {
                val path = skill.path
                if (path.isNullOrBlank()) showStatus(OpencodeFrontendBundle.message("settings.opencode.skills.no.path"))
                else openDirectory(path)
            }
        )
    }

    private fun openGlobalConfigFile() {
        if (globalConfigPath.isBlank()) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.config.unknown"))
            return
        }
        openConfigFile(globalConfigPath, ConfigScopeDto.GLOBAL)
    }

    override fun isModified(): Boolean = false
}
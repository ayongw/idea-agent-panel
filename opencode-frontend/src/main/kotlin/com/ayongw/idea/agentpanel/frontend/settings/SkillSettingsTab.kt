package com.ayongw.idea.agentpanel.frontend.settings

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.shared.ConfigScopeDto
import com.ayongw.idea.agentpanel.shared.SkillDto
import com.ayongw.idea.agentpanel.shared.SkillSourceDto
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

    override val title: String = AgentPanelBundle.message("settings.agent.tab.skills")

    private val sourcesRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val loadedLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val cards = SettingsCardList(AgentPanelBundle.message("settings.agent.filter"))

    /** 全局配置文件路径（快照给出，可能尚不存在，打开时后端会自动建出） */
    private var globalConfigPath: String = ""

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val sourcesBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JBLabel(AgentPanelBundle.message("settings.agent.skills.sources")).apply {
                            font = JBUI.Fonts.label().asBold()
                        },
                        BorderLayout.WEST
                    )
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                            add(
                                JButton(AgentPanelBundle.message("settings.agent.config.open")).apply {
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
                        JBLabel(AgentPanelBundle.message("settings.agent.skills.list")).apply {
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
                buildHeader(title, AgentPanelBundle.message("settings.agent.skills.hint")) { reload() },
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
            loadedLabel.text = AgentPanelBundle.message(
                "settings.agent.count",
                snapshot.discoveredSkills.size
            )
        }
    }

    private fun renderSources(sources: List<SkillSourceDto>) {
        sourcesRow.removeAll()
        if (sources.isEmpty()) {
            sourcesRow.add(buildHint(AgentPanelBundle.message("settings.agent.skills.sources.empty")))
        } else {
            sources.forEach { sourcesRow.add(sourceLabel(it)) }
        }
        sourcesRow.revalidate()
        sourcesRow.repaint()
    }

    private fun sourceLabel(source: SkillSourceDto): JComponent {
        val kind = AgentPanelBundle.message(
            if (source.declared) "settings.agent.skills.source.declared"
            else "settings.agent.skills.source.convention"
        )
        val suffix = if (source.exists) kind
        else kind + " · " + AgentPanelBundle.message("settings.agent.skills.source.missing")
        return wrappedHintLabel("• " + source.path, suffix)
    }

    private fun skillCard(skill: SkillDto): SettingsCard {
        val description = skill.description?.takeIf { it.isNotBlank() }
            ?: AgentPanelBundle.message("settings.agent.skills.no.description")
        return SettingsCard(title = skill.id, subtitle = description).withAction(
            buildGearButton(AgentPanelBundle.message("settings.agent.skills.open.dir")) {
                val path = skill.path
                if (path.isNullOrBlank()) showStatus(AgentPanelBundle.message("settings.agent.skills.no.path"))
                else openDirectory(path)
            }
        )
    }

    private fun openGlobalConfigFile() {
        if (globalConfigPath.isBlank()) {
            showStatus(AgentPanelBundle.message("settings.agent.config.unknown"))
            return
        }
        openConfigFile(globalConfigPath, ConfigScopeDto.GLOBAL)
    }

    override fun isModified(): Boolean = false
}
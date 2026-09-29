package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.RuleFileDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 规则面板：`AGENTS.md`（真正生效的规则载体）编辑 + `instructions` 只读展示
 *
 * 规则不走 JSON，直接做文本读写；`instructions` 在 v2 只接受不解析，此处仅作兼容展示。
 */
internal class RuleSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.rules")

    private val fileCombo = JComboBox<String>()
    private val existsLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val editor = JBTextArea()
    private val instructionsArea = JBTextArea().apply {
        rows = 5
        isEditable = false
    }

    private var ruleFiles: List<RuleFileDto> = emptyList()

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        fileCombo.addActionListener { loadSelectedFile() }

        val head = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.rules.file"), fileCombo)
            .addComponent(existsLabel)
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.rules.reload")).apply {
                            addActionListener { loadSelectedFile() }
                        }
                    )
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                            addActionListener { saveSelectedFile() }
                        }
                    )
                }
            )
            .panel

        val instructions = FormBuilder.createFormBuilder()
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.rules.instructions.hint")))
            .addComponent(JBScrollPane(instructionsArea))
            .panel

        return JPanel(BorderLayout()).apply {
            add(buildHeader(title) { reload() }, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(head, BorderLayout.NORTH)
                    add(JBScrollPane(editor), BorderLayout.CENTER)
                    add(instructions, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            ruleFiles = snapshot.ruleFiles
            val model = DefaultComboBoxModel<String>()
            snapshot.ruleFiles.forEach { model.addElement(it.path) }
            fileCombo.model = model
            fileCombo.selectedIndex = if (snapshot.ruleFiles.isEmpty()) -1 else 0
            instructionsArea.text = snapshot.instructions.joinToString("\n")
            if (snapshot.ruleFiles.isNotEmpty()) loadSelectedFile()
        }
    }

    private fun selectedFile(): RuleFileDto? = ruleFiles.getOrNull(fileCombo.selectedIndex)

    private fun loadSelectedFile() {
        val file = selectedFile() ?: return
        existsLabel.text = OpencodeFrontendBundle.message(
            if (file.exists) "settings.opencode.rules.exists" else "settings.opencode.rules.missing"
        )
        val project = currentProject() ?: return
        runAsync({ content ->
            editor.text = content?.content.orEmpty()
            editor.caretPosition = 0
        }) {
            SettingsRpcApi.getInstance().readRuleFile(project.projectId(), file.path)
        }
    }

    private fun saveSelectedFile() {
        val file = selectedFile() ?: return
        val project = currentProject() ?: return
        runWrite({ loadSelectedFile() }) {
            SettingsRpcApi.getInstance().saveRuleFile(project.projectId(), file.path, editor.text)
        }
    }

    override fun isModified(): Boolean = false
}
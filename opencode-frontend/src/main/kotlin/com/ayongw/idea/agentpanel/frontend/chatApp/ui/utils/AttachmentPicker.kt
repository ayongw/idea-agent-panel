package com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.shared.ContextFileDto
import com.ayongw.idea.agentpanel.shared.ContextKind
import java.io.File
import java.time.LocalDateTime
import javax.swing.JFileChooser

/**
 * 本机（工作区内外）文件与目录选择：`JFileChooser` → 会话附件。
 *
 * 服务端的 `fs` 系列接口以 `location.directory` 为基准，被限制在工作区内，
 * 故本机任意文件一律走本地选择器。
 */
object AttachmentPicker {

    /** 打开文件选择器（多选，文件与目录皆可）；取消时返回空列表 */
    fun pick(parent: java.awt.Component?, basePath: String?): List<ContextFileDto> {
        val chooser = JFileChooser().apply {
            dialogTitle = AgentPanelBundle.message("chat.attach.dialog.title")
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            basePath?.let { dir -> File(dir).takeIf { it.isDirectory }?.let { currentDirectory = it } }
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.orEmpty().map(::toAttachment)
    }

    /** 文件 → 上下文附件（目录标记为 `directory`） */
    fun toAttachment(file: File): ContextFileDto {
        val isDirectory = file.isDirectory
        return ContextFileDto(
            path = file.absolutePath,
            name = file.name,
            summary = if (isDirectory) MentionSupport.DIRECTORY_SUMMARY else "",
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = if (isDirectory) ContextKind.DIRECTORY else ContextKind.FILE
        )
    }
}
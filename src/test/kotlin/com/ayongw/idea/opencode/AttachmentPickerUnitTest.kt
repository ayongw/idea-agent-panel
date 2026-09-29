package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.AttachmentPicker
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.shared.ContextKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * 本机文件选择结果的类型判定（文件 / 目录 → 上下文附件）。
 */
class AttachmentPickerUnitTest {

    @Test
    fun directoryBecomesDirectoryAttachment() {
        val dir = Files.createTempDirectory("oc-attach-dir").toFile()
        try {
            val attachment = AttachmentPicker.toAttachment(dir)

            assertEquals(ContextKind.DIRECTORY, attachment.kind)
            assertEquals(dir.absolutePath, attachment.path)
            assertEquals(dir.name, attachment.name)
            assertEquals(MentionSupport.DIRECTORY_SUMMARY, attachment.summary)
        } finally {
            dir.delete()
        }
    }

    @Test
    fun fileBecomesFileAttachment() {
        val file = Files.createTempFile("oc-attach", ".kt").toFile()
        try {
            val attachment = AttachmentPicker.toAttachment(file)

            assertEquals(ContextKind.FILE, attachment.kind)
            assertEquals(file.absolutePath, attachment.path)
            assertEquals("", attachment.summary)
        } finally {
            file.delete()
        }
    }
}
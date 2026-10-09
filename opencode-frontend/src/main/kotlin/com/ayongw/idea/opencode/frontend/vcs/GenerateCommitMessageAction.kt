package com.ayongw.idea.opencode.frontend.vcs

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.VcsDataKeys

/**
 * 提交窗口「生成提交信息」按钮（TSD-33）。
 *
 * 挂载点：`Vcs.MessageActionGroup` —— 提交信息输入框所在区域，平台自带的
 * 「消息历史」按钮也在这一组（`ShowMessageHistoryAction`）。
 *
 * 为什么不用 `VcsCommitMessageInterceptor`：该扩展点 **2024.2 起已移除**，
 * 2026.2.3 的 `VcsExtensionPoints.xml` 里已不存在（详见 TSD-33 §2.1）。
 *
 * 回填通路（public API，非反射）：[VcsDataKeys.COMMIT_MESSAGE_CONTROL] → `CommitMessageI.setCommitMessage`。
 *
 * 当前为 **S1 阶段**：仅验证挂载点与数据通路可达，尚未接入 opencode 生成。
 */
class GenerateCommitMessageAction : AnAction(), DumbAware {

    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 后台线程更新：读 VCS 数据不能上 EDT
        e.presentation.text = OpencodeFrontendBundle.message("vcs.commit.generate")
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        // S1 只验证「挂载点 + 数据通路」可达，尚未接入 opencode 生成（见 TSD-33 §8）
        log.info(
            "生成提交信息（S1 探针）：project=${project?.name}, " +
                "提交信息控件=${if (control != null) "可达" else "不可达（当前不在提交信息区域）"}"
        )
    }

    companion object {
        /** Action id（plugin.xml 注册同名） */
        const val ID = "OpenCode.GenerateCommitMessage"
    }
}
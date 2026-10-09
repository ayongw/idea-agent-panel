package com.ayongw.idea.opencode.frontend.vcs

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.settings.OpenCodeSettingsConfigurable
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.idea.opencode.shared.CommitMessageRequestDto
import com.ayongw.idea.opencode.shared.CommitMessageResultDto
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.project.projectId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import kotlinx.coroutines.launch
import java.io.File

/**
 * 提交窗口「生成提交信息」按钮（TSD-33）。
 *
 * 挂载点：`Vcs.MessageActionGroup` —— 提交信息输入框所在区域，平台自带的
 * 「消息历史」按钮也在这一组（`ShowMessageHistoryAction`）。
 *
 * 为什么不用 `VcsCommitMessageInterceptor`：该扩展点 **2024.2 起已移除**，
 * 2026.2.3 的 `VcsExtensionPoints.xml` 里已不存在（见 TSD-33 §2.1）。
 *
 * 回填通路（public API，非反射）：[VcsDataKeys.COMMIT_MESSAGE_CONTROL] → `CommitMessageI.setCommitMessage`。
 *
 * 模型：只用设置页指定的模型，**空则不发起请求**，弹提示 + 「去设置」跳转（§5.1.1）。
 */
class GenerateCommitMessageAction : AnAction(), DumbAware {

    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)

    /** 生成中标志：按钮禁用 + 文案切「生成中…」，防重复点击（跨线程，故用原子变量） */
    private val generating = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 提交窗口工具栏（Vcs.MessageActionGroup）按图标按钮渲染 action，
        // 未设 icon 时按钮区域为空 = 看不见。图标在代码里设而不是 plugin.xml：
        // xml 的 icon 属性只接受资源路径，内置图标需走 AllIcons 常量。
        e.presentation.icon = AllIcons.Actions.AiIntentionBulb
        val busy = generating.get()
        e.presentation.text = OpencodeFrontendBundle.message(
            if (busy) "vcs.commit.generate.running" else "vcs.commit.generate"
        )
        e.presentation.isEnabledAndVisible = e.project != null && !busy
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (control == null) {
            log.warn("生成提交信息：当前不在提交信息区域，无法回填")
            return
        }

        // 当前已有文本：CommitMessageI 是只写接口（无 getter），读走 COMMIT_MESSAGE_DOCUMENT。
        // 在 EDT 点击时取快照，生成期间用户新写的内容不受影响。
        val currentText = e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)?.text?.trim().orEmpty()

        // 选中变更从提交窗口的 DataContext 取（提交工具窗口内即当前 changelist 的变更）
        val selectedChanges = e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        if (selectedChanges.isEmpty()) {
            log.info("生成提交信息：提交窗口当前没有选中变更，跳过")
            return
        }

        if (!generating.compareAndSet(false, true)) {
            log.info("生成提交信息：已有生成任务进行中，忽略重复点击")
            return
        }

        val app = ApplicationManager.getApplication()
        val scope = CoroutineScopeHolder.getInstance(project).createScope("GenerateCommitMessage")
        scope.launch {
            try {
                // 模型 = 设置页「默认模型」下拉的值（opencode GLOBAL 配置），未配置则不发起请求
                val defaultModel = runCatching {
                    ChatRepositoryRpcApi.getInstance().getDefaultModel(project.projectId())
                }.getOrNull()
                val providerId = defaultModel?.providerID.orEmpty()
                val modelId = defaultModel?.modelID.orEmpty()
                if (providerId.isBlank() || modelId.isBlank()) {
                    log.info("生成提交信息：未指定默认模型，引导去设置")
                    app.invokeLater { notifyNoModel(project) }
                    return@launch
                }

                // 采集要跑 git CLI（起子进程）+ 读 VCS 内容，必须离开 EDT
                val prompt = withContextIo {
                    val context = CommitMessageContextCollector.collect(
                        basePath = project.basePath,
                        changes = selectedChanges,
                        includeUnstaged = true
                    )
                    CommitMessagePromptBuilder.build(context)
                }
                log.info("生成提交信息：prompt ${prompt.length} 字符，模型 $providerId/$modelId")

                val result = runCatching {
                    ChatRepositoryRpcApi.getInstance().generateCommitMessage(
                        project.projectId(),
                        CommitMessageRequestDto(prompt, providerId, modelId)
                    )
                }.getOrElse {
                    CommitMessageResultDto(
                        success = false,
                        reason = CommitMessageResultDto.REASON_UNAVAILABLE,
                        detail = it.message ?: "调用失败"
                    )
                }

                app.invokeLater {
                    if (result.success) {
                        appendToCommitMessage(control, currentText, result.text)
                    } else {
                        log.warn("生成提交信息失败 reason=${result.reason} detail=${result.detail}")
                        notifyFailed(project, result)
                    }
                }
            } finally {
                generating.set(false)
            }
        }
    }

    /**
     * 已有内容则换行追加（不覆盖用户手写），空则直接填入。
     *
     * 模型的换行一律压成单行：提交信息框通常只有两三行高，多行会把输入框撑大，
     * 主体 + 空行 + 脚注的 Conventional Commits 结构也正好一行放得下。
     */
    private fun appendToCommitMessage(control: CommitMessageI, currentText: String, text: String) {
        val generated = text.trim().lines().joinToString(" ") { it.trim() }.ifBlank { return }
        control.setCommitMessage(
            if (currentText.isBlank()) generated else "$currentText\n$generated"
        )
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private fun notifyNoModel(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                OpencodeFrontendBundle.message("vcs.commit.generate.no.model.title"),
                OpencodeFrontendBundle.message("vcs.commit.generate.no.model.content"),
                NotificationType.INFORMATION
            )
            .addAction(
                object : AnAction(OpencodeFrontendBundle.message("vcs.commit.generate.no.model.action")) {
                    override fun actionPerformed(e: AnActionEvent) {
                        // 直接跳到「模型」页：默认模型下拉就在那里
                        OpenCodeSettingsConfigurable.selectTab(OpenCodeSettingsConfigurable.MODELS_TAB_INDEX)
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
                    }
                }
            )
            .notify(project)
    }

    private fun notifyFailed(project: Project, result: CommitMessageResultDto) {
        if (result.reason == CommitMessageResultDto.REASON_NO_MODEL) {
            notifyNoModel(project)
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                OpencodeFrontendBundle.message("vcs.commit.generate.failed.title"),
                result.detail.ifBlank { OpencodeFrontendBundle.message("vcs.commit.generate.failed.title") },
                NotificationType.WARNING
            )
            .notify(project)
    }

    companion object {
        /** Action id（plugin.xml 注册同名） */
        const val ID = "OpenCode.GenerateCommitMessage"

        /** 复用既有通知分组 */
        private const val NOTIFICATION_GROUP = "OpenCode.Server"
    }
}
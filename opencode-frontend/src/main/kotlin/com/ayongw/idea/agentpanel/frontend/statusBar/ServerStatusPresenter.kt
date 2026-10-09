package com.ayongw.idea.agentpanel.frontend.statusBar

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.shared.ServerStateDto
import com.intellij.icons.AllIcons
import java.awt.Color
import javax.swing.Icon

/**
 * Server 状态 → 状态栏展示规格（纯逻辑，无 Swing 布局，便于单测）。
 *
 * 状态栏空间有限，只能表达「图标 + 色 + 一行提示」，因此与面板内的
 * [com.ayongw.idea.agentpanel.frontend.chatApp.ui.ServerStatusStrip] 分工：
 * - 状态栏：**全局可见性**（工具窗关闭时也能看到）+ 快捷入口（点击弹窗）
 * - 面板内 strip：完整文案、失败详情、重试/凭据/设置等可操作按钮
 *
 * @param icon 状态栏图标
 * @param color 图标着色（null 用 LAF 默认前景色）
 * @param tooltip 悬停提示
 * @param summary 弹窗里的单行状态摘要
 * @param attention true 表示异常态（失败 / 需凭据），UI 应额外强调
 */
data class ServerStatusPresentation(
    val icon: Icon,
    val color: Color?,
    val tooltip: String,
    val summary: String,
    val attention: Boolean
)

/**
 * 状态映射器：把 [ServerStateDto] 翻成 [ServerStatusPresentation]。
 *
 * 与后端 `OpenCodeServerState` / `OpenCodeServerFailure` 枚举同名对齐（见 TSD-31）。
 */
object ServerStatusPresenter {

    /** 异常态着色：与面板内状态条同一色，两处观感不割裂 */
    private val ATTENTION_COLOR: Color get() = ChatAppColors.Status.warning

    fun present(state: ServerStateDto, strings: Strings): ServerStatusPresentation =
        when (state.state) {
            // REUSING 是**终态健康**而非进行中：复用外部实例走的是同一条探测/校验路径，
            // 校验通过后才落到 REUSING（后端只在拉起自有实例时才发布 READY，见
            // OpenCodeServerManager.reuseExternal）。此前把 REUSING 归到 progress 分支，
            // 导致外部实例场景下状态栏永远显示「Detecting the OpenCode Server...」。
            ServerStateDto.STATE_REUSING -> ServerStatusPresentation(
                icon = AllIcons.General.InspectionsOK,
                color = null,
                tooltip = strings.reusing(state),
                summary = strings.reusing(state),
                attention = false
            )

            ServerStateDto.STATE_READY -> ServerStatusPresentation(
                icon = AllIcons.General.InspectionsOK,
                color = null,
                tooltip = strings.ready(state),
                summary = strings.ready(state),
                attention = false
            )

            ServerStateDto.STATE_DISCOVERING,
            ServerStateDto.STATE_REUSING,
            ServerStateDto.STATE_STARTING,
            ServerStateDto.STATE_STOPPING ->
                ServerStatusPresentation(
                    icon = AllIcons.General.BalloonInformation,
                    color = null,
                    tooltip = strings.progress(state),
                    summary = strings.progress(state),
                    attention = false
                )

            ServerStateDto.STATE_NEEDS_CREDENTIALS -> ServerStatusPresentation(
                icon = AllIcons.General.BalloonWarning,
                color = ATTENTION_COLOR,
                tooltip = strings.needsCredentials(),
                summary = strings.needsCredentials(),
                attention = true
            )

            ServerStateDto.STATE_FAILED -> ServerStatusPresentation(
                icon = AllIcons.General.BalloonError,
                color = ATTENTION_COLOR,
                tooltip = strings.failed(state),
                summary = strings.failed(state),
                attention = true
            )

            // IDLE / STOPPED：服务端点不在运行，但插件本身无异常，用中性图标
            else -> ServerStatusPresentation(
                icon = AllIcons.General.BalloonInformation,
                color = null,
                tooltip = strings.idle(),
                summary = strings.idle(),
                attention = false
            )
        }

    /** 文案来源（抽成接口便于单测注入固定文案） */
    interface Strings {
        fun ready(state: ServerStateDto): String

        /** 复用外部实例：默认复用 ready 文案，各实现可给出更明确说明 */
        fun reusing(state: ServerStateDto): String = ready(state)

        fun progress(state: ServerStateDto): String
        fun needsCredentials(): String
        fun failed(state: ServerStateDto): String
        fun idle(): String
    }
}
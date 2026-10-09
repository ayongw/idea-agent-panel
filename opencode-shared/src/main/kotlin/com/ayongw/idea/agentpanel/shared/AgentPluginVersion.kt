package com.ayongw.idea.agentpanel.shared

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * 插件自身版本号（agent 无关的中立工具）。
 *
 * 唯一真源：运行中的插件描述符。改动插件 id 时只需改这里的 [PLUGIN_ID]
 * ——原先 `ProjectServerHost` 与设置页各存一份硬编码，改名时必漏其一。
 */
object AgentPluginVersion {

    /** 必须与 plugin.xml 的 `<id>` 一致 */
    const val PLUGIN_ID = "com.ayongw.idea.idea-agent-panel"

    /** 查不到插件时展示的占位（沙箱 / 异常态），不抛异常以免影响 UI 渲染 */
    const val UNKNOWN = "unknown"

    /** 运行中的插件版本；取不到返回 [UNKNOWN] */
    fun get(): String =
        runCatching { PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version }
            .getOrNull()
            ?: UNKNOWN
}

package com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils

/**
 * 按像素宽度省略文本（尾部 `…`），用于窄窗口下收缩模型名等可变长标签。
 *
 * 纯逻辑：宽度测量以 [measure] 注入（生产传 `FontMetrics::stringWidth`，测试传假测量），
 * 无 Swing 依赖，便于覆盖 CJK/拉丁混排与边界。
 */
object TextElipsis {

    /** 省略号 */
    const val ELLIPSIS = "…"

    /**
     * 把 [text] 截到 [maxWidth] 像素以内（超出则尾部补 [ELLIPSIS]）。
     *
     * @param maxWidth 可用宽度（<=0 视为不限，原样返回）
     * @param measure 文本宽度测量函数
     * @return 放得下则原样返回；放不下则截断并补省略号；单字符都放不下时返回空串
     */
    fun elide(text: String, maxWidth: Int, measure: (String) -> Int): String {
        if (text.isEmpty() || maxWidth <= 0) return text
        if (measure(text) <= maxWidth) return text
        if (measure(ELLIPSIS) > maxWidth) return ""
        val keep = text.length - 1
        // 逐字符回退：模型名多为拉丁/短中文，按字符数近似即可，避免引入 FontRenderContext 依赖
        for (n in keep downTo 1) {
            val candidate = text.substring(0, n) + ELLIPSIS
            if (measure(candidate) <= maxWidth) return candidate
        }
        return ELLIPSIS
    }
}
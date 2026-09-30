package com.ayongw.idea.opencode.frontend.chatApp.ui

/**
 * 滚动粘底策略（TSD-30 §4.2 C-滚动 / §5.2）：仅当用户已在底部（阈值内）时才允许自动贴底。
 *
 * 判定依据是视口几何：
 * `viewPosition.y + extentHeight >= viewSize.height - 阈值`
 *
 * 纯逻辑、无 Swing 依赖，可单测；阈值取「一行高度量级」，由 UI 侧经 [threshold] 注入（`JBUI.scale`）。
 */
class ScrollPolicy(private val threshold: Int = DEFAULT_THRESHOLD) {

    /**
     * 是否处于「贴底位置」（用户在底部，允许自动滚动跟到最新）
     *
     * @param viewPositionY 视口当前顶边位置（`viewport.viewPosition.y`）
     * @param extentHeight  视口可见高度（`viewport.extentSize.height`）
     * @param viewSizeHeight 内容总高度（`viewport.viewSize.height`）
     */
    fun shouldStickToBottom(viewPositionY: Int, extentHeight: Int, viewSizeHeight: Int): Boolean =
        viewPositionY + extentHeight >= viewSizeHeight - threshold

    companion object {
        /** 默认阈值（逻辑像素；UI 侧应按 `JBUI.scale` 换算后传入实际行高量级） */
        const val DEFAULT_THRESHOLD = 24
    }
}

package com.ayongw.idea.opencode.frontend.chatApp.ui

/**
 * UI 刷新合并器（TSD-30 §4.2 C-刷新 / §5.3）：同一窗口内的多次刷新请求合并为一次执行。
 *
 * 语义：leading-edge 节流——距上次执行已过窗口期才放行，否则合并（跳过）。
 * 后端已有 75ms 节流推送，前端窗口取更小值（20ms），只合并同一事件循环内的重复请求，
 * 不叠加端到端延迟，同时保证「一次布局 + 一次滚动判定」的收口。
 *
 * 纯逻辑、无 Swing 依赖，可单测；时间源 [now] 可注入（测试用假时钟）。
 */
class ListUpdateCoalescer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private var lastRunAt: Long? = null

    /** 上次执行距现在是否已过窗口期（决定本次请求放行还是合并）；从未执行过视为可运行 */
    val shouldRun: Boolean
        get() {
            val last = lastRunAt ?: return true
            return now() - last >= windowMs
        }

    /**
     * 请求一次刷新。
     *
     * @return true 表示放行（应执行布局 + 滚动判定）；false 表示仍在窗口内（合并，跳过）
     */
    fun request(): Boolean {
        if (!shouldRun) return false
        lastRunAt = now()
        return true
    }

    companion object {
        /** 合并窗口（毫秒）：低于后端 75ms 节流，只合并同一事件循环内的重复请求 */
        const val DEFAULT_WINDOW_MS = 20L
    }
}

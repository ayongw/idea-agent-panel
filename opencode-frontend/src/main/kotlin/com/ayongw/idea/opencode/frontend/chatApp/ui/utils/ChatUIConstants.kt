package com.ayongw.idea.opencode.frontend.chatApp.ui.utils

import java.awt.Dimension

object ChatUIConstants {

    object Spacing {
        const val TINY = 2
        const val SMALL = 4
        const val MEDIUM = 6
        const val NORMAL = 8
        const val LARGE = 12
        const val XLARGE = 16
    }

    object MessageBubble {
        const val MIN_WIDTH = 120
        const val MAX_WIDTH = 420
        const val CORNER_RADIUS = 16
        const val VERTICAL_MARGIN = 6
        const val HORIZONTAL_MARGIN = 12
        const val INNER_PADDING = 10

        const val CONTENT_WRAP_WIDTH = MAX_WIDTH - 2 * HORIZONTAL_MARGIN - 2 * INNER_PADDING

        /** 助手消息头部头像边长 */
        const val AVATAR_SIZE = 18

        /** 头像与名称之间的间距 */
        const val AVATAR_GAP = 6
    }

    object Panel {
        /**
         * 工具窗内容最小宽度（逻辑像素）。
         *
         * 底部工具条是 `BorderLayout(WEST=按钮组, EAST=用量)`：JDK BorderLayout 对
         * WEST/EAST **各自按 preferred 摆放、从不压缩**，两端 preferred 之和超过可用宽度时
         * 不是裁剪而是**重叠绘制**（实测见 temp/layout-probe/Probe2.java）。
         * 用量外层已精简为「↑输入 ↓输出」（≈73px），按钮组可响应式省略，
         * 450px 有富余；窄于此值则拖动工具窗会被 IDE 拒绝，避免信息被压没。
         */
        const val MIN_WIDTH = 450
    }

    object ThinkingIndicator {
        const val PADDING = 8
        const val DOT_COUNT = 3
        const val DOT_DIAMETER = 6
        const val DOT_SPACING = 4
        const val CYCLE_MS = 900
        const val STAGGER_MS = 200
        const val ANIMATION_PERIOD_MS = 16
        const val MIN_ALPHA = 0.25
        const val MAX_ALPHA = 1.0
    }

    object Button {
        val ACTION_BUTTON_SIZE = Dimension(24, 24)
        val LARGE_ACTION_BUTTON_SIZE = Dimension(28, 28)
        val SEND_BUTTON_SIZE = Dimension(32, 32)
    }

    object Input {
        const val MIN_HEIGHT = 40
        const val MAX_HEIGHT = 100
        const val MIN_WIDTH = 100
        const val TEXT_AREA_PAD_VERTICAL = 4
        const val TEXT_AREA_PAD_HORIZONTAL = 8
        const val BORDER_THICKNESS = 1
    }

    object SearchBar {
        const val FIELD_MAX_WIDTH = 400
        const val FIELD_COLUMNS = 32
    }

    object SessionList {
        const val ITEM_HEIGHT = 56
        const val MIN_WIDTH = 240
        const val PREF_WIDTH = 280

        /**
         * 行尾删除按钮的固定占位宽度（像素）。
         *
         * 常驻占位、仅 hover 时显示图标：几何固定 → 删除热区可由「距行右边 x 像素」直接算出，
         * 无需在点击时反查组件（单元格 renderer 每次绘制都重建，拿不到稳定引用）。
         */
        const val DELETE_SLOT = 22
    }

    object TopBar {
        const val TAB_HEIGHT = 30
        const val TAB_MAX_WIDTH = 220
        const val TAB_TITLE_MAX_CHARS = 24
        const val TAB_INDICATOR_THICKNESS = 2
        const val TAB_CLOSE_BUTTON_SIZE = 18
    }

    object AllSessionsPopup {
        const val WIDTH = 400
        const val HEIGHT = 460
    }

    object LargeContent {
        const val MAX_TEXT_LENGTH = 10 * 1024 // 10KB
        const val LINES_PER_PAGE = 200

        /** 代码块折叠预览行数（超出部分需点「显示完整」展开） */
        const val CODE_PREVIEW_LINES = 12

        /** 代码块展开后的最大高度（超出在块内滚动，避免撑爆消息列表） */
        const val CODE_MAX_HEIGHT = 320

        /** 代码块单行行高（用于换算预览/展开高度，避免布局自我测量死循环） */
        const val CODE_LINE_HEIGHT = 18
    }
}
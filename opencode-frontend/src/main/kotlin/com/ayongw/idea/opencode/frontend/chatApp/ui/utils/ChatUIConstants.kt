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
        const val INNER_PADDING = 16

        const val CONTENT_WRAP_WIDTH = MAX_WIDTH - 2 * HORIZONTAL_MARGIN - 2 * INNER_PADDING
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
        const val MAX_CODE_LINES = 500
    }
}
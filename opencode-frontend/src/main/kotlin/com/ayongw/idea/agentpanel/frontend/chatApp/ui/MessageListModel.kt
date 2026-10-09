package com.ayongw.idea.agentpanel.frontend.chatApp.ui

/**
 * 消息列表顺序模型（TSD-30 §4.1 A4 / §5.2）：消息 id 顺序是布局槽位的唯一真源。
 *
 * `gridy` 必须由这里的索引派生（而非全表数组下标），增删后统一重排，
 * 避免「数组下标当布局槽位 / 组件总数减气泡数推断 filler」两类错配（P1-4）。
 *
 * 纯逻辑、无 Swing 依赖，可单测。
 */
class MessageListModel {

    private val orderedIds = mutableListOf<String>()

    /** 当前顺序（最早在前） */
    val ids: List<String> get() = orderedIds.toList()

    val size: Int get() = orderedIds.size

    /** 以最新服务端顺序整体替换（服务端消息集合是权威） */
    fun sync(ids: List<String>) {
        orderedIds.clear()
        orderedIds.addAll(ids)
    }

    /** 按 id 查槽位（gridy）；不存在返回 -1 */
    fun indexOf(id: String): Int = orderedIds.indexOf(id)

    /** 是否包含指定 id */
    fun contains(id: String): Boolean = id in orderedIds
}

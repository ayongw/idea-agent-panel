package com.ayongw.idea.agentpanel.backend.agent.opencode.server

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** 端口可用性探测（可注入假实现，便于单测覆盖「被占用 / 可用」矩阵） */
fun interface OpenCodePortProbe {
    fun isAvailable(port: Int): Boolean
}

/** 端口分配结果 */
sealed interface OpenCodePortAllocation {
    /** 命中可用端口；[attempts] 为依次尝试过的端口 */
    data class Allocated(val port: Int, val attempts: List<Int>) : OpenCodePortAllocation

    /** 候选端口全部不可用（备用端口耗尽 / 用户指定端口被占） */
    data class Exhausted(val attempts: List<Int>) : OpenCodePortAllocation
}

/**
 * 端口选择（TSD-31 §3.4）：按候选顺序取第一个可用端口（默认 4096 → 备用端口）
 *
 * 纯逻辑，可用性判定经 [OpenCodePortProbe] 注入。
 */
class OpenCodePortAllocator(
    private val probe: OpenCodePortProbe = LoopbackPortProbe(),
) {

    fun allocate(candidates: List<Int>): OpenCodePortAllocation {
        val attempts = ArrayList<Int>(candidates.size)
        candidates.forEach { port ->
            attempts += port
            if (probe.isAvailable(port)) return OpenCodePortAllocation.Allocated(port, attempts.toList())
        }
        return OpenCodePortAllocation.Exhausted(attempts.toList())
    }
}

/**
 * 默认探测实现：以「能否在回环地址上绑定」判定端口是否空闲
 *
 * 未用 `NetUtils.findAvailableSocketPort()`——它返回随机空闲端口，无法满足「4096 优先、冲突才走备用端口」的确定性顺序。
 */
class LoopbackPortProbe : OpenCodePortProbe {

    override fun isAvailable(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        }
        true
    } catch (_: IOException) {
        false
    }
}
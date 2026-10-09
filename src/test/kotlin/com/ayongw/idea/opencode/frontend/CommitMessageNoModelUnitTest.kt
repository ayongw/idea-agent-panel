package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.shared.CommitMessageRequestDto
import com.ayongw.idea.opencode.shared.CommitMessageResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型未配置时的契约（TSD-33 §5.1.1 简化后的规则）。
 *
 * 规则：只用用户指定的默认模型；**未指定时不发起任何请求**，返回可引导的失败原因。
 */
class CommitMessageNoModelUnitTest {

    private fun request(providerId: String, modelId: String) =
        CommitMessageRequestDto(prompt = "生成提交信息", providerId = providerId, modelId = modelId)

    @Test
    fun 未配置模型时的请求形状应为空() {
        // 前端在未配置时直接引导，不会发请求；这里的形状约束用于保证「空值可表达」
        val r = request("", "")
        assertTrue(r.providerId.isBlank())
        assertTrue(r.modelId.isBlank())
        assertTrue("prompt 仍需非空", r.prompt.isNotBlank())
    }

    @Test
    fun 失败原因常量稳定() {
        // 前端按这些常量分支（NO_MODEL 走「去设置」引导），改名会静默破坏引导
        assertEquals("NO_MODEL", CommitMessageResultDto.REASON_NO_MODEL)
        assertEquals("UNAVAILABLE", CommitMessageResultDto.REASON_UNAVAILABLE)
        assertEquals("TIMEOUT", CommitMessageResultDto.REASON_TIMEOUT)
    }

    @Test
    fun 未配置模型的结果是可引导的失败而非静默成功() {
        val result = CommitMessageResultDto(
            success = false,
            reason = CommitMessageResultDto.REASON_NO_MODEL,
            detail = "请先在设置中指定「提交信息生成模型」"
        )
        assertFalse("未配置模型不得返回成功", result.success)
        assertEquals(CommitMessageResultDto.REASON_NO_MODEL, result.reason)
        assertTrue("应带可展示的说明", result.detail.isNotBlank())
    }

    @Test
    fun 成功结果只带文本不带失败原因() {
        val result = CommitMessageResultDto(success = true, text = "feat: 增加删除按钮")
        assertTrue(result.success)
        assertEquals("feat: 增加删除按钮", result.text)
        assertTrue("成功时不应带 reason", result.reason.isBlank())
    }
}

package com.ai.assistance.operit.services.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 多 Agent 协作的独立窗口状态总线。
 *
 * 每个 agent 的"内心思考流"（thinking/speech 原文 + 工具调用）实时推到这里，
 * UI 层（聊天页协作面板）按 chatId 订阅渲染。这些内容不会写入主聊天历史，
 * 因此其他 agent 的模型上下文里永远看不到彼此的内心思考。
 */
data class MultiAgentWindowState(
    val chatId: String,
    val agentId: String,
    val name: String,
    val roleTitle: String,
    /** thinking / done / error */
    val phase: String,
    /** 该 agent 的完整流式输出（内心思考 + 公开发言 + 工具过程） */
    val text: String,
    /** 本轮发起过的工具调用名 */
    val toolCalls: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

object MultiAgentUiBus {
    const val PHASE_THINKING = "thinking"
    const val PHASE_DONE = "done"
    const val PHASE_ERROR = "error"

    private val _windows = MutableStateFlow<Map<String, MultiAgentWindowState>>(emptyMap())
    val windows: StateFlow<Map<String, MultiAgentWindowState>> = _windows.asStateFlow()

    private fun key(chatId: String, agentId: String) = "$chatId::$agentId"

    fun initAgent(window: MultiAgentWindowState) {
        _windows.value = _windows.value + (key(window.chatId, window.agentId) to window)
    }

    fun updateText(chatId: String, agentId: String, text: String, phase: String = PHASE_THINKING) {
        val k = key(chatId, agentId)
        val current = _windows.value[k] ?: return
        _windows.value = _windows.value +
            (k to current.copy(text = text, phase = phase, updatedAt = System.currentTimeMillis()))
    }

    fun markPhase(chatId: String, agentId: String, phase: String) {
        val k = key(chatId, agentId)
        val current = _windows.value[k] ?: return
        _windows.value = _windows.value +
            (k to current.copy(phase = phase, updatedAt = System.currentTimeMillis()))
    }

    fun addToolCall(chatId: String, agentId: String, toolName: String) {
        val k = key(chatId, agentId)
        val current = _windows.value[k] ?: return
        if (current.toolCalls.contains(toolName)) return
        _windows.value = _windows.value +
            (k to current.copy(
                toolCalls = current.toolCalls + toolName,
                updatedAt = System.currentTimeMillis()
            ))
    }

    /** 协作结束后保留窗口内容供用户回看；开启新一轮协作时覆盖 */
    fun clearChat(chatId: String) {
        _windows.value = _windows.value.filterKeys { !it.startsWith("$chatId::") }
    }

    fun activeWindows(chatId: String?): List<MultiAgentWindowState> {
        if (chatId.isNullOrBlank()) return emptyList()
        return windows.value.values
            .filter { it.chatId == chatId }
            .sortedBy { it.agentId }
    }
}
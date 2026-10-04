package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.R
import com.ai.assistance.operit.services.core.MultiAgentCollaborationPreferences.FEATURE_KEY
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuToggleDefinition
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuToggleHookParams
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuTogglePlugin
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuTogglePluginRegistry
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuToggleSlots

/**
 * 多 Agent 协作输入菜单开关。
 *
 * 通过 InputMenuTogglePluginRegistry 挂到聊天输入菜单（Agent/Classic 两种
 * 样式都会渲染），开关状态走 featureToggles（与思考模式、工具开关同一通道），
 * key 为 [FEATURE_KEY]。开启后本会话每次发送都会触发多 agent 协作。
 */
object MultiAgentInputMenuTogglePlugin : InputMenuTogglePlugin {
    override val id: String = "builtin.multi-agent-collaboration-toggle"

    fun register() {
        InputMenuTogglePluginRegistry.register(this)
    }

    override fun createToggles(
        params: InputMenuToggleHookParams
    ): List<InputMenuToggleDefinition> {
        return listOf(
            InputMenuToggleDefinition(
                id = FEATURE_KEY,
                titleRes = R.string.multi_agent_toggle_title,
                descriptionRes = R.string.multi_agent_toggle_desc,
                icon = "groups",
                isChecked = params.featureStates[FEATURE_KEY] ?: false,
                slot = InputMenuToggleSlots.GENERAL,
                onToggle = {
                    // ApiConfigDelegate.toggleFeature 负责翻转并落盘 featureToggles
                    params.onToggleFeature(FEATURE_KEY)
                }
            )
        )
    }
}
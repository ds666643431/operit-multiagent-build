package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.services.ChatServiceUiBridge
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 多 Agent 协作编排器
 *
 * 设计目标：在同一会话内让多个 AI 以"人类合作"的方式分工讨论项目开工：
 * - 每个 agent 是一张动态创建/复用的专用角色卡（Agent-1..N），职能 persona
 *   由 [FunctionalPrompts.buildMultiAgentCharacterSetting] 生成（统筹/架构/评审/执行……）；
 * - agent 互相看到彼此的发言（历史按角色隔离，其他 agent 的输出桥接为
 *   [From role: xxx]），可以讨论、反驳、指出对方方案的缺点；
 * - 发言顺序与轮数由协作规划模型（FunctionType.MULTI_AGENT 绑定的模型，
 *   默认跟随主模型）按 JSON 规划，多个轮次让讨论自然往返，而非主模型单向调用；
 * - 复用 MessageProcessingDelegate.sendUserMessage 的群组编排通道，
 *   消息正常流式渲染、落库、计费，UI 中每个 agent 以自己的名字显示。
 */
class MultiAgentOrchestrator(
    private val context: Context,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val chatHistoryDelegate: ChatHistoryDelegate,
    private val messageProcessingDelegate: MessageProcessingDelegate,
    private val attachmentDelegate: AttachmentDelegate,
    private val apiConfigDelegate: ApiConfigDelegate,
    private val uiStateDelegate: com.ai.assistance.operit.ui.features.chat.viewmodel.UiStateDelegate,
    private val getEnhancedAiService: () -> EnhancedAIService?,
    private val uiBridge: ChatServiceUiBridge
) {
    companion object {
        private const val TAG = "MultiAgentOrchestrator"
        // 等待单个 agent 完成发言的上限（与群组编排一致）
        private const val AGENT_TURN_TIMEOUT_MS = 180_000L
        // 规划模型调用上限
        private const val PLANNER_TIMEOUT_MS = 60_000L
        // 参与协作上下文的最近消息条数
        private const val RECENT_CONTEXT_MESSAGES = 10
    }

    /** 单个协作 agent 的描述 */
    data class AgentSpec(
        val id: String,
        val name: String,
        val roleTitle: String,
        val roleDuty: String
    )

    /** 规划结果：每轮由哪些 agent 依次发言 */
    data class PlannedSpeech(
        val rounds: List<List<String>>
    )

    // 每个会话最多一个进行中的编排任务
    private val activeOrchestrations = ConcurrentHashMap<String, Job>()
    private val characterCardManager = CharacterCardManager.getInstance(context)

    /** 是否有协作正在进行（供 UI 展示/禁用按钮） */
    fun isOrchestrating(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return activeOrchestrations[chatId]?.isActive == true
    }

    /** 取消进行中的协作（用户点了停止） */
    fun cancelOrchestration(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        activeOrchestrations.remove(chatId)?.cancel()
    }

    /**
     * 尝试接管本轮用户消息进行多 agent 协作。
     *
     * @return true 表示已接管（调用方不再走普通发送）；false 表示协作未启用
     *         或输入无效，调用方按原逻辑处理。
     */
    fun tryOrchestrate(
        chatId: String,
        promptFunctionType: PromptFunctionType,
        turnOptions: ChatTurnOptions
    ): Boolean {
        if (!MultiAgentCollaborationPreferences.isEnabledBlocking(context)) {
            return false
        }
        if (activeOrchestrations[chatId]?.isActive == true) {
            AppLogger.w(TAG, "协作进行中，忽略新请求: chatId=$chatId")
            return true
        }
        val originalUserText = messageProcessingDelegate.userMessage.value.text.trim()
        val hasAttachments = attachmentDelegate.attachments.value.isNotEmpty()
        if (originalUserText.isBlank() && !hasAttachments) {
            // 空输入交给普通发送逻辑处理（它会提示或忽略）
            return false
        }
        val job = coroutineScope.launch {
            orchestrate(
                chatId = chatId,
                originalUserText = originalUserText,
                hasAttachments = hasAttachments,
                promptFunctionType = promptFunctionType,
                turnOptions = turnOptions
            )
        }
        activeOrchestrations[chatId] = job
        job.invokeOnCompletion { activeOrchestrations.remove(chatId, job) }
        return true
    }

    private suspend fun orchestrate(
        chatId: String,
        originalUserText: String,
        hasAttachments: Boolean,
        promptFunctionType: PromptFunctionType,
        turnOptions: ChatTurnOptions
    ) {
        val agentCount = MultiAgentCollaborationPreferences.getAgentCount(context)
        val agents = buildAgentSpecs(agentCount)
        try {
            // 清空输入框，防止普通发送路径重复消费同一条输入
            if (originalUserText.isNotBlank()) {
                messageProcessingDelegate.updateUserMessage("")
            }

            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Processing(context.getString(R.string.multi_agent_planning))
            )

            // 1. 构建并落库本轮用户消息，作为所有 agent 的共享输入
            val currentChat = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }
            val workspacePath = currentChat?.workspace
            val workspaceEnv = currentChat?.workspaceEnv
            val attachments = attachmentDelegate.attachments.value
            val replyToMessage = uiBridge.getReplyToMessage()
            val isFirstMessage = !chatHistoryDelegate.hasUserMessage(chatId)
            val titleFallback = if (isFirstMessage) {
                fallbackTitle(originalUserText, attachments)
            } else {
                null
            }
            val finalUserMessageContent =
                messageProcessingDelegate.buildUserMessageContentForGroupOrchestration(
                    messageText = originalUserText,
                    attachments = attachments,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    replyToMessage = replyToMessage,
                    chatId = chatId
                )
            val userMessage = ChatMessage(
                sender = "user",
                content = finalUserMessageContent,
                roleName = context.getString(R.string.message_role_user),
                displayMode =
                    if (turnOptions.hideUserMessage) {
                        ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
                    } else {
                        ChatMessageDisplayMode.NORMAL
                    }
            )
            chatHistoryDelegate.addMessageToChat(userMessage, chatId)
            titleFallback?.let { chatHistoryDelegate.updateChatTitle(chatId, it) }

            // 清理输入面板状态（与群组编排一致）
            attachmentDelegate.clearAttachments()
            uiBridge.resetAttachmentPanelState()
            uiBridge.clearReplyToMessage()

            // 2. 为每个 agent 解析/创建角色卡（复用已有卡片，persona 按当前职能刷新）
            val agentCardIds = ensureAgentCharacterCards(agents)
            if (agentCardIds.size < agents.size) {
                AppLogger.e(TAG, "部分 agent 角色卡不可用: ${agents.size - agentCardIds.size} 个缺失")
            }

            // 3. 规划本轮协作的发言顺序与轮数
            val participantNamesText = buildParticipantNamesText(agents)
            val planned = planSpeechOrder(
                agents = agents,
                userText = originalUserText,
                recentContext = recentDiscussion(chatId)
            ) ?: PlannedSpeech(
                // 规划失败时的兜底轮次：首轮全部按序发言，次轮第一位收敛
                rounds = listOf(
                    agents.map { it.id },
                    listOf(agents.first().id)
                )
            )
            if (planned.rounds.isEmpty()) {
                messageProcessingDelegate.setInputProcessingStateForChat(
                    chatId,
                    InputProcessingState.Completed
                )
                return
            }
            AppLogger.d(TAG, "协作规划完成: rounds=${planned.rounds.size}, agents=${agents.size}")

            // 4. 顺序执行每一轮发言（复用群组编排通道：角色隔离、流式、落库）
            var firstSpeakerHandled = false
            planned.rounds.forEachIndexed { roundIndex, speakerIds ->
                for (agentId in speakerIds) {
                    val agent = agents.firstOrNull { it.id == agentId } ?: continue
                    val agentRoleCardId = agentCardIds[agentId] ?: continue
                    messageProcessingDelegate.setInputProcessingStateForChat(
                        chatId,
                        InputProcessingState.Processing(
                            context.getString(
                                R.string.multi_agent_agent_speaking,
                                agent.name
                            )
                        )
                    )
                    val targetTurnCounter =
                        messageProcessingDelegate.getTurnCompleteCounter(chatId) + 1L
                    // 首个发言者复用已落库的用户消息原文；后续发言者不再携带新用户消息，
                    // 其请求输入为空，由历史中的 [From user]/[From role: xxx] 驱动
                    val isFirstSpeaker = !firstSpeakerHandled
                    val agentMessage = if (isFirstSpeaker) originalUserText else ""
                    messageProcessingDelegate.sendUserMessage(
                        attachments = emptyList(),
                        chatId = chatId,
                        messageTextOverride = agentMessage,
                        prebuiltMessageContent = if (isFirstSpeaker) finalUserMessageContent else null,
                        proxySenderNameOverride = null,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        promptFunctionType = promptFunctionType,
                        roleCardId = agentRoleCardId,
                        enableThinking = apiConfigDelegate.enableThinkingMode.value,
                        enableMemoryAutoUpdate = false,
                        maxTokens = 0,
                        tokenUsageThreshold = Double.MAX_VALUE,
                        replyToMessage = null,
                        isAutoContinuation = false,
                        enableSummary = false,
                        chatModelConfigIdOverride = null,
                        chatModelIndexOverride = null,
                        memorySpaceIdOverride = null,
                        suppressUserMessageInHistory = true,
                        isGroupOrchestrationTurn = true,
                        groupParticipantNamesText = participantNamesText,
                        turnOptions = turnOptions.copy(persistTurn = true)
                    )
                    firstSpeakerHandled = true
                    val completed = awaitAgentTurn(chatId, targetTurnCounter)
                    if (!completed) {
                        AppLogger.w(TAG, "agent 发言等待超时: agent=${agent.name}, round=${roundIndex + 1}")
                        continue
                    }
                }
            }

            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Completed
            )
            AppLogger.d(TAG, "多 agent 协作完成: chatId=$chatId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "多 agent 协作被取消: chatId=$chatId")
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Idle
            )
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "多 agent 协作失败", e)
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Error(
                    context.getString(R.string.multi_agent_orchestration_failed, e.message ?: "")
                )
            )
        }
    }

    // ---------- 角色卡 ----------

    /**
     * 为每个 agent 解析/创建角色卡，返回 agent -> 实际角色卡 id 的映射。
     *
     * CharacterCardManager.createCharacterCard 会生成随机 UUID 作为 id，
     * 因此创建后把真实 id 记录到偏好存储；已有记录则复用卡片，
     * persona 与当前职能不一致时刷新（数量配置变化后职能会重新分配）。
     */
    private suspend fun ensureAgentCharacterCards(agents: List<AgentSpec>): Map<String, String> {
        val useEnglish = !LocaleUtils.usesChineseContent(context)
        val mapping = mutableMapOf<String, String>()
        for (agent in agents) {
            val desiredSetting = FunctionalPrompts.buildMultiAgentCharacterSetting(
                agentName = agent.name,
                roleTitle = agent.roleTitle,
                roleDuty = agent.roleDuty,
                useEnglish = useEnglish
            )
            val existingId = MultiAgentCollaborationPreferences.getAgentCardId(context, agent.id)
            val existing = existingId?.let {
                runCatching { characterCardManager.getCharacterCard(it) }.getOrNull()
            }
            if (existing == null) {
                val createdId = runCatching {
                    characterCardManager.createCharacterCard(
                        CharacterCard(
                            id = "multi_agent_" + agent.id,
                            name = agent.name,
                            description = context.getString(R.string.multi_agent_card_description),
                            characterSetting = desiredSetting,
                            marks = context.getString(R.string.multi_agent_card_marks)
                        )
                    )
                }.getOrNull()
                if (createdId != null) {
                    MultiAgentCollaborationPreferences.saveAgentCardId(context, agent.id, createdId)
                    mapping[agent.id] = createdId
                }
            } else {
                if (existing.characterSetting != desiredSetting || existing.name != agent.name) {
                    runCatching {
                        characterCardManager.updateCharacterCard(
                            existing.copy(
                                name = agent.name,
                                characterSetting = desiredSetting
                            )
                        )
                    }.onFailure {
                        AppLogger.e(TAG, "刷新 agent 角色卡失败: ${agent.name}", it)
                    }
                }
                mapping[agent.id] = existing.id
            }
        }
        return mapping
    }

    // ---------- 规划 ----------

    private suspend fun planSpeechOrder(
        agents: List<AgentSpec>,
        userText: String,
        recentContext: String
    ): PlannedSpeech? {
        val service = getEnhancedAiService() ?: return null
        val plannerService = runCatching {
            service.getAIServiceForFunction(FunctionType.MULTI_AGENT)
        }.getOrNull() ?: return null
        val modelParameters = runCatching {
            MultiServiceManager(context).getModelParametersForFunction(FunctionType.MULTI_AGENT)
        }.getOrNull() ?: emptyList()
        val prompt = FunctionalPrompts.buildMultiAgentPlannerPrompt(
            agentLines = agents.joinToString("\n") {
                "- id: ${it.id}, name: ${it.name}, role: ${it.roleTitle}"
            },
            userText = userText,
            recentContext = recentContext,
            useEnglish = !LocaleUtils.usesChineseContent(context)
        )
        val contentBuilder = StringBuilder()
        val ok = runCatching {
            val stream = plannerService.sendMessage(
                context = context,
                chatHistory = listOf(
                    PromptTurn(kind = PromptTurnKind.USER, content = prompt)
                ),
                modelParameters = modelParameters,
                enableThinking = false,
                stream = false,
                preserveThinkInHistory = false,
                recordTokenUsage = false
            )
            withTimeoutOrNull(PLANNER_TIMEOUT_MS) {
                stream.collect { chunk -> contentBuilder.append(chunk) }
            } != null
        }.getOrElse {
            AppLogger.e(TAG, "协作规划模型调用失败", it)
            false
        }
        if (!ok) return null
        val raw = ChatUtils.removeThinkingContent(contentBuilder.toString()).trim()
        return parsePlannedSpeech(raw, agents.map { it.id }.toSet())
    }

    private fun parsePlannedSpeech(rawContent: String, agentIds: Set<String>): PlannedSpeech? {
        if (rawContent.isBlank()) return null
        val trimmed = rawContent.trim()
        val jsonText = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            trimmed.contains("{") && trimmed.contains("}") -> {
                val start = trimmed.indexOf("{")
                val end = trimmed.lastIndexOf("}")
                if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
            }
            else -> return null
        }
        return runCatching {
            val root = JSONObject(jsonText)
            val roundsArray = root.optJSONArray("rounds") ?: return@runCatching null
            val rounds = mutableListOf<List<String>>()
            for (i in 0 until roundsArray.length()) {
                val roundItem = roundsArray.opt(i)
                val speakerIds = mutableListOf<String>()
                when (roundItem) {
                    is JSONArray -> {
                        for (j in 0 until roundItem.length()) {
                            val id = extractAgentId(roundItem.opt(j))
                            if (id != null && id in agentIds && !speakerIds.contains(id)) {
                                speakerIds.add(id)
                            }
                        }
                    }
                    is JSONObject -> {
                        val id = extractAgentId(roundItem)
                        if (id != null && id in agentIds) speakerIds.add(id)
                    }
                    is String -> {
                        if (roundItem in agentIds) speakerIds.add(roundItem)
                    }
                }
                if (speakerIds.isNotEmpty()) rounds.add(speakerIds)
            }
            if (rounds.isEmpty()) null else PlannedSpeech(rounds)
        }.getOrNull()
    }

    private fun extractAgentId(item: Any?): String? {
        return when (item) {
            is String -> item.trim().takeIf { it.isNotEmpty() }
            is JSONObject -> {
                item.optString("id")
                    .ifBlank { item.optString("agentId") }
                    .ifBlank { item.optString("name") }
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    // ---------- 辅助 ----------

    private suspend fun awaitAgentTurn(chatId: String, targetCounter: Long): Boolean {
        return withTimeoutOrNull(AGENT_TURN_TIMEOUT_MS) {
            messageProcessingDelegate.turnCompleteCounterByChatId.first { counters ->
                (counters[chatId] ?: 0L) >= targetCounter
            }
            true
        } ?: false
    }

    private fun buildAgentSpecs(count: Int): List<AgentSpec> {
        val safeCount = count.coerceIn(MultiAgentCollaborationPreferences.MIN_AGENTS, MultiAgentCollaborationPreferences.MAX_AGENTS)
        return (1..safeCount).map { index ->
            AgentSpec(
                id = "agent-$index",
                name = context.getString(R.string.multi_agent_agent_name, index),
                roleTitle = context.getString(R.string.multi_agent_role_title, index),
                roleDuty = context.getString(R.string.multi_agent_role_duty, index)
            )
        }
    }

    private fun buildParticipantNamesText(agents: List<AgentSpec>): String {
        val useEnglish = !LocaleUtils.usesChineseContent(context)
        val names = agents.map { it.name } + context.getString(R.string.message_role_user)
        return if (useEnglish) names.joinToString(", ") else names.joinToString("、")
    }

    private suspend fun recentDiscussion(chatId: String): String {
        val runtime = chatHistoryDelegate.getRuntimeChatHistory(chatId)
        val recent = runtime.takeLast(RECENT_CONTEXT_MESSAGES)
        return recent.joinToString("\n") { message ->
            val label = if (message.sender == "user") {
                context.getString(R.string.message_role_user)
            } else {
                message.roleName.ifBlank { "AI" }
            }
            val cleaned = ChatUtils.removeThinkingContent(message.content)
            "$label: ${cleaned.take(400)}"
        }
    }

    private fun fallbackTitle(userText: String, attachments: List<com.ai.assistance.operit.data.model.AttachmentInfo>): String {
        return attachments.firstOrNull()?.fileName?.trim()?.takeIf { it.isNotBlank() }
            ?: userText.replace("\n", " ").trim().take(20).ifBlank {
                context.getString(R.string.multi_agent_chat_title)
            }
    }
}
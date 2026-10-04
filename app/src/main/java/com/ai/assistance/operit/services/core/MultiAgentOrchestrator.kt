package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.EnhancedAIService
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 多 Agent 协作编排器（v2：并行协作）
 *
 * 与 v1（串行轮流换角色卡发言）的本质区别：
 *
 * 1. 全员同时激活：用户发出指令后，N 个 agent 在同一时刻并行收到指令并开始生成。
 *    总耗时 ≈ 最慢一个 agent 的时间，而不是 N 个 agent 依次生成的时间总和。
 * 2. 公开/私密信息分离：
 *    - 每个 agent 的完整输出流（内心思考 + 工具过程 + 发言）只进入该 agent 的
 *      独立窗口（[MultiAgentUiBus]），实时流式显示，用户随时点开查看；
 *    - 编排器从完整输出中剥离 <speech>...</speech> 里的公开发言写入主聊天历史，
 *      其他 agent 在后续轮次中也只能看到彼此的公开发言，看不到内心思考。
 * 3. 轮次结构：首答轮（全员并行，互不可见）→ 互评轮（全员并行，基于上一轮
 *    公开发言互相反驳/补充）→ 收敛轮（由统筹角色汇总出最终开工方案）。
 *
 * 每个 agent 是一张动态创建/复用的专用角色卡（persona 注入职能），模型通过
 * FunctionType.MULTI_AGENT 绑定（默认跟随主模型，可独立配置 API/模型）。
 */
class MultiAgentOrchestrator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
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
        // 单个 agent 一轮生成的超时（海外高延迟模型留足余量）
        private const val AGENT_TURN_TIMEOUT_MS = 300_000L
        // agent 角色卡 id 前缀（持久存在，persona 随职能刷新）
        private const val AGENT_CARD_ID_PREFIX = "multi_agent_collab_"
        private val SPEECH_REGEX = Regex("<speech>([\\s\\S]*?)</speech>", RegexOption.IGNORE_CASE)
    }

    /** 单个协作 agent 的描述 */
    data class AgentSpec(
        val id: String,
        val name: String,
        val roleTitle: String,
        val roleDuty: String
    )

    /** 一轮中一个 agent 的产出 */
    private data class AgentRoundResult(
        val agent: AgentSpec,
        val speech: String?
    )

    // 每个会话最多一个进行中的编排任务
    private val activeOrchestrations = ConcurrentHashMap<String, Job>()
    private val characterCardManager = CharacterCardManager.getInstance(context)

    /** 是否有协作正在进行（供 UI 展示） */
    fun isOrchestrating(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return activeOrchestrations[chatId]?.isActive == true
    }

    /** 取消进行中的协作：编排任务本身 + 全部并行子协程 */
    fun cancelOrchestration(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        activeOrchestrations.remove(chatId)?.cancel()
    }

    /**
     * 尝试接管本轮用户消息进行多 agent 并行协作。
     *
     * @return true 表示已接管；false 表示协作未启用或输入无效，调用方按原逻辑处理。
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
            return false
        }
        val job = coroutineScope.launch {
            orchestrateParallel(
                chatId = chatId,
                originalUserText = originalUserText,
                promptFunctionType = promptFunctionType,
                turnOptions = turnOptions
            )
        }
        activeOrchestrations[chatId] = job
        job.invokeOnCompletion { activeOrchestrations.remove(chatId, job) }
        return true
    }

    // ==================== 并行协作主流程 ====================

    private suspend fun orchestrateParallel(
        chatId: String,
        originalUserText: String,
        promptFunctionType: PromptFunctionType,
        turnOptions: ChatTurnOptions
    ) {
        val agentCount = MultiAgentCollaborationPreferences.getAgentCount(context)
        val discussionRounds = MultiAgentCollaborationPreferences.getDiscussionRounds(context)
        val agents = buildAgentSpecs(agentCount)
        val useEnglish = !com.ai.assistance.operit.util.LocaleUtils.usesChineseContent(context)

        try {
            // 清空输入，防止普通发送路径重复消费
            if (originalUserText.isNotBlank()) {
                messageProcessingDelegate.updateUserMessage("")
            }

            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Processing(context.getString(R.string.multi_agent_activating))
            )

            // 1. 构建用户指令内容（附件/工作区引用并入），落库一条用户消息（所有人可见）
            val currentChat = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }
            val workspacePath = currentChat?.workspace
            val workspaceEnv = currentChat?.workspaceEnv
            val attachments = attachmentDelegate.attachments.value
            val userDirective = messageProcessingDelegate
                .buildUserMessageContentForGroupOrchestration(
                    messageText = originalUserText,
                    attachments = attachments,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    replyToMessage = uiBridge.getReplyToMessage(),
                    chatId = chatId
                )
            val userMessage = ChatMessage(
                sender = "user",
                content = userDirective,
                roleName = context.getString(R.string.message_role_user),
                displayMode =
                    if (turnOptions.hideUserMessage) ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
                    else ChatMessageDisplayMode.NORMAL
            )
            chatHistoryDelegate.addMessageToChat(userMessage, chatId)
            if (!chatHistoryDelegate.hasUserMessage(chatId)) {
                // 首条消息给个标题兜底
                chatHistoryDelegate.updateChatTitle(
                    chatId,
                    originalUserText.replace("\n", " ").trim().take(20)
                        .ifBlank { context.getString(R.string.multi_agent_chat_title) }
                )
            }

            // 清理输入面板状态
            attachmentDelegate.clearAttachments()
            uiBridge.resetAttachmentPanelState()
            uiBridge.clearReplyToMessage()

            // 2. 准备 agent 角色卡 + 初始化独立窗口
            val agentCardIds = ensureAgentCharacterCards(agents, useEnglish)
            MultiAgentUiBus.clearChat(chatId)
            agents.forEach { agent ->
                MultiAgentUiBus.initAgent(
                    MultiAgentWindowState(
                        chatId = chatId,
                        agentId = agent.id,
                        name = agent.name,
                        roleTitle = agent.roleTitle,
                        phase = MultiAgentUiBus.PHASE_THINKING,
                        text = ""
                    )
                )
            }

            val service = getEnhancedAiService() ?: run {
                messageProcessingDelegate.setInputProcessingStateForChat(
                    chatId, InputProcessingState.Error(
                        context.getString(R.string.message_ai_service_not_initialized)
                    )
                )
                return
            }

            // 3. 每个 agent 一份独立上下文：
            //    开头是输出协议，之后累积：指令(带上一轮他人发言摘要) + 自己的公开发言
            val contexts = agents.associate { agent ->
                agent.id to mutableListOf(
                    PromptTurn(
                        kind = PromptTurnKind.SYSTEM,
                        content = FunctionalPrompts.multiAgentSpeechProtocolPrompt(useEnglish)
                    )
                )
            }
            val speechesByAgent = mutableMapOf<String, MutableList<String>>()

            // 4. 首答轮：全员并行，同时接收用户指令，互相不可见
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Processing(
                    context.getString(R.string.multi_agent_round_first, agents.size)
                )
            )
            val firstDirective =
                "[${context.getString(R.string.message_role_user)}]\n$userDirective"
            val firstResults = runParallelRound(
                service = service,
                chatId = chatId,
                agents = agents,
                agentCardIds = agentCardIds,
                contexts = contexts,
                directive = firstDirective,
                roundLabel = "first",
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv
            )
            firstResults.forEach { r ->
                r.speech?.let {
                    speechesByAgent.getOrPut(r.agent.id) { mutableListOf() }.add(it)
                }
            }
            val activeAgents = firstResults.filter { it.speech != null }.map { it.agent }
            if (activeAgents.isEmpty()) {
                messageProcessingDelegate.setInputProcessingStateForChat(
                    chatId,
                    InputProcessingState.Error(
                        context.getString(R.string.multi_agent_orchestration_failed, "all agents failed")
                    )
                )
                return
            }

            // 5. 互评轮（可多轮）：全员并行，基于上一轮公开发言互相反驳/补充
            for (round in 2..discussionRounds) {
                val othersDigest = buildSpeechDigest(speechesByAgent, agents)
                val roundDirective = buildString {
                    if (useEnglish) {
                        appendLine("Round $round of team discussion. Below are ALL teammates' public speeches so far (including yours):")
                        appendLine(othersDigest)
                        appendLine()
                        appendLine("Speak now: respond directly — agree, rebut with concrete evidence, point out flaws, supplement, or refine your own position. Stay focused on the user's request.")
                    } else {
                        appendLine("团队讨论第 $round 轮。以下是所有成员（含你自己）到目前为止的公开发言：")
                        appendLine(othersDigest)
                        appendLine()
                        appendLine("请发言：直接回应他们的观点——认同、用具体依据反驳、指出缺点、补充，或修正自己的立场。聚焦用户的请求。")
                    }
                }
                messageProcessingDelegate.setInputProcessingStateForChat(
                    chatId,
                    InputProcessingState.Processing(
                        context.getString(R.string.multi_agent_round_discuss, round - 1)
                    )
                )
                val results = runParallelRound(
                    service = service,
                    chatId = chatId,
                    agents = activeAgents,
                    agentCardIds = agentCardIds,
                    contexts = contexts,
                    directive = roundDirective,
                    roundLabel = "discuss-$round",
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv
                )
                results.forEach { r ->
                    r.speech?.let {
                        speechesByAgent.getOrPut(r.agent.id) { mutableListOf() }.add(it)
                    }
                }
            }

            // 6. 收敛轮：由统筹角色（agent-1）汇总出最终可执行方案
            val leadAgent = agents.first()
            val allSpeeches = buildSpeechDigest(speechesByAgent, agents)
            val convergeDirective = buildString {
                if (useEnglish) {
                    appendLine("You are the lead. All discussion rounds are complete. Full public speeches:")
                    appendLine(allSpeeches)
                    appendLine()
                    appendLine("Converge now: produce the FINAL kickoff plan for the user — conclusions, division of work, concrete steps, risks and mitigations. This is the deliverable the user will act on.")
                } else {
                    appendLine("你是团队统筹。所有讨论轮已结束。全部公开发言：")
                    appendLine(allSpeeches)
                    appendLine()
                    appendLine("请收敛：为用户产出最终开工方案——结论、分工、具体步骤、风险与对策。这是用户要拿去执行的交付物。")
                }
            }
            MultiAgentUiBus.markPhase(chatId, leadAgent.id, MultiAgentUiBus.PHASE_THINKING)
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Processing(
                    context.getString(R.string.multi_agent_round_converge, leadAgent.name)
                )
            )
            runParallelRound(
                service = service,
                chatId = chatId,
                agents = listOf(leadAgent),
                agentCardIds = agentCardIds,
                contexts = contexts,
                directive = convergeDirective,
                roundLabel = "converge",
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv
            )

            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId, InputProcessingState.Completed
            )
            AppLogger.d(TAG, "并行协作完成: chatId=$chatId, agents=${agents.size}, rounds=$discussionRounds")
        } catch (e: CancellationException) {
            AppLogger.d(TAG, "并行协作被取消: chatId=$chatId")
            markThinkingWindowsError(chatId)
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId, InputProcessingState.Idle
            )
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "并行协作失败", e)
            markThinkingWindowsError(chatId)
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Error(
                    context.getString(R.string.multi_agent_orchestration_failed, e.message ?: "")
                )
            )
        }
    }

    private fun markThinkingWindowsError(chatId: String) {
        MultiAgentUiBus.activeWindows(chatId).forEach {
            if (it.phase == MultiAgentUiBus.PHASE_THINKING) {
                MultiAgentUiBus.markPhase(chatId, it.agentId, MultiAgentUiBus.PHASE_ERROR)
            }
        }
    }

    /**
     * 运行一轮并行生成：所有 agent 同时启动，各自流式输出到独立窗口；
     * 完成者立即把公开发言写入主聊天历史（先完成先显示）。
     */
    private suspend fun runParallelRound(
        service: EnhancedAIService,
        chatId: String,
        agents: List<AgentSpec>,
        agentCardIds: Map<String, String>,
        contexts: Map<String, MutableList<PromptTurn>>,
        directive: String,
        roundLabel: String,
        workspacePath: String?,
        workspaceEnv: String?
    ): List<AgentRoundResult> {
        return coroutineScope.async {
            agents.map { agent ->
                async {
                    runAgentTurn(
                        service = service,
                        chatId = chatId,
                        agent = agent,
                        roleCardId = agentCardIds[agent.id],
                        agentContext = contexts[agent.id] ?: mutableListOf(),
                        directive = directive,
                        roundLabel = roundLabel,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv
                    )
                }
            }.awaitAll()
        }.await()
    }

    /**
     * 单个 agent 的一轮生成（私有会话）：
     * - chatId 加 agent 后缀 → 独立执行上下文，不干扰主聊天状态；
     * - isSubTask=true → 不触发前台通知/输入状态广播；
     * - 完整流实时推独立窗口；结束后剥离 <speech> 写主历史。
     */
    private suspend fun runAgentTurn(
        service: EnhancedAIService,
        chatId: String,
        agent: AgentSpec,
        roleCardId: String?,
        agentContext: MutableList<PromptTurn>,
        directive: String,
        roundLabel: String,
        workspacePath: String?,
        workspaceEnv: String?
    ): AgentRoundResult {
        if (roleCardId == null) {
            MultiAgentUiBus.markPhase(chatId, agent.id, MultiAgentUiBus.PHASE_ERROR)
            return AgentRoundResult(agent, null)
        }
        val buffer = StringBuilder()
        return try {
            val options = EnhancedAIService.SendMessageOptions(
                message = directive,
                chatHistory = agentContext.toList(),
                chatId = "$chatId#agent-${agent.id}",
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                promptFunctionType = PromptFunctionType.CHAT,
                enableThinking = apiConfigDelegate.enableThinkingMode.value,
                enableMemoryAutoUpdate = false,
                maxTokens = 0,
                tokenUsageThreshold = Double.MAX_VALUE,
                isSubTask = true,
                roleCardId = roleCardId,
                onToolInvocation = { toolName ->
                    MultiAgentUiBus.addToolCall(chatId, agent.id, toolName)
                }
            )
            val stream = service.sendMessage(options)
            val ok = withTimeoutOrNull(AGENT_TURN_TIMEOUT_MS) {
                stream.collect { chunk ->
                    buffer.append(chunk)
                    MultiAgentUiBus.updateText(
                        chatId, agent.id, buffer.toString(),
                        MultiAgentUiBus.PHASE_THINKING
                    )
                }
                true
            } ?: false

            val full = buffer.toString()
            val speech = extractSpeech(full)

            // 累积私有上下文：指令 + 自己的公开发言（内心思考不进上下文）
            agentContext.add(PromptTurn(kind = PromptTurnKind.USER, content = directive))
            agentContext.add(
                PromptTurn(
                    kind = PromptTurnKind.ASSISTANT,
                    content = speech ?: full.take(2000)
                )
            )

            if (ok && speech != null) {
                MultiAgentUiBus.updateText(chatId, agent.id, full, MultiAgentUiBus.PHASE_DONE)
                // 公开发言写入主聊天历史（先完成先显示）
                val (provider, modelName) = try {
                    service.getDisplayProviderAndModelForFunction(
                        functionType = FunctionType.CHAT,
                        chatModelConfigIdOverride = null,
                        chatModelIndexOverride = null
                    )
                } catch (e: Exception) {
                    Pair("", "")
                }
                chatHistoryDelegate.addMessageToChat(
                    ChatMessage(
                        sender = "ai",
                        content = speech.trim(),
                        roleName = agent.name,
                        provider = provider,
                        modelName = modelName
                    ),
                    chatId
                )
                AppLogger.d(
                    TAG,
                    "agent 发言完成: agent=${agent.name}, round=$roundLabel, len=${speech.length}"
                )
                AgentRoundResult(agent, speech)
            } else {
                MultiAgentUiBus.markPhase(chatId, agent.id, MultiAgentUiBus.PHASE_ERROR)
                AppLogger.w(TAG, "agent 轮失败或无发言: agent=${agent.name}, round=$roundLabel, ok=$ok")
                AgentRoundResult(agent, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "agent 生成异常: agent=${agent.name}", e)
            MultiAgentUiBus.markPhase(chatId, agent.id, MultiAgentUiBus.PHASE_ERROR)
            AgentRoundResult(agent, null)
        }
    }

    /**
     * 从完整输出流中剥离公开发言。
     * 找 <speech>...</speech>；找不到则返回 null（内心思考绝不写入主历史）。
     */
    private fun extractSpeech(full: String): String? {
        val match = SPEECH_REGEX.find(full) ?: return null
        val content = match.groupValues[1].trim()
        return content.ifBlank { null }
    }

    /** 汇总各 agent 的公开发言（含全部轮次），桥接给成员间互相可见 */
    private fun buildSpeechDigest(
        speechesByAgent: Map<String, List<String>>,
        agents: List<AgentSpec>
    ): String {
        return agents.mapNotNull { agent ->
            val speeches = speechesByAgent[agent.id] ?: return@mapNotNull null
            if (speeches.isEmpty()) return@mapNotNull null
            val content = speeches.joinToString("\n---\n")
            "[From agent: ${agent.name}]\n$content"
        }.joinToString("\n\n")
    }

    // ==================== 角色卡 ====================

    private fun agentCardId(agentId: String): String = AGENT_CARD_ID_PREFIX + agentId

    /**
     * 为每个 agent 解析/创建角色卡，返回 agentId -> 实际角色卡 id。
     * createCharacterCard 会生成随机 UUID，创建后把真实 id 存回偏好映射；
     * 已有映射则复用卡片，persona 与当前职能不一致时刷新。
     */
    private suspend fun ensureAgentCharacterCards(
        agents: List<AgentSpec>,
        useEnglish: Boolean
    ): Map<String, String> {
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
                            id = agentCardId(agent.id),
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

    // ==================== Agent 定义 ====================

    private fun buildAgentSpecs(count: Int): List<AgentSpec> {
        val safeCount = count.coerceIn(
            MultiAgentCollaborationPreferences.MIN_AGENTS,
            MultiAgentCollaborationPreferences.MAX_AGENTS
        )
        return (1..safeCount).map { index ->
            val (titleRes, dutyRes) = when (index) {
                1 -> R.string.multi_agent_role_lead to R.string.multi_agent_duty_lead
                2 -> R.string.multi_agent_role_architect to R.string.multi_agent_duty_architect
                3 -> R.string.multi_agent_role_reviewer to R.string.multi_agent_duty_reviewer
                else -> R.string.multi_agent_role_implementer to R.string.multi_agent_duty_implementer
            }
            AgentSpec(
                id = "agent-$index",
                name = context.getString(R.string.multi_agent_agent_name, index),
                roleTitle = context.getString(titleRes),
                roleDuty = context.getString(dutyRes)
            )
        }
    }
}
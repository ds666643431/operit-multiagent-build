package com.ai.assistance.operit.services.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 多 Agent 协作功能的配置存储。
 *
 * 启用开关复用 ApiPreferences 的 featureToggles 通道（featureKey =
 * [FEATURE_KEY]，聊天输入菜单的开关都走这里）；agent 数量单独存放在本文件
 * 私有的 DataStore 中；模型/API 绑定不在这里存，而是走 FunctionalConfigManager
 * 的 FunctionType.MULTI_AGENT 映射（默认跟随主模型配置，用户可在功能模型
 * 配置页为协作功能单独指定模型，无需额外配置 API）。
 */
object MultiAgentCollaborationPreferences {
    /** 聊天输入菜单的 feature toggle key */
    const val FEATURE_KEY = "multi_agent_collaboration"

    private val Context.multiAgentDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "multi_agent_collaboration")

    private val AGENT_COUNT = intPreferencesKey("agent_count")
    private val DISCUSSION_ROUNDS = intPreferencesKey("discussion_rounds")
    private val AGENT_CARD_IDS = stringPreferencesKey("agent_card_ids_json")

    const val DEFAULT_AGENT_COUNT = 3
    const val MAX_AGENTS = 10
    const val MIN_AGENTS = 2
    const val DEFAULT_DISCUSSION_ROUNDS = 1
    const val MIN_DISCUSSION_ROUNDS = 0
    const val MAX_DISCUSSION_ROUNDS = 4

    fun isEnabledFlow(context: Context): Flow<Boolean> {
        return ApiPreferences.getInstance(context).featureToggleFlow(FEATURE_KEY, false)
    }

    suspend fun isEnabled(context: Context): Boolean {
        return isEnabledFlow(context).first()
    }

    /** 非挂起上下文读取启用状态（内部使用 runBlocking，勿在主线程频繁调用） */
    fun isEnabledBlocking(context: Context): Boolean {
        return kotlinx.coroutines.runBlocking { isEnabled(context) }
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        ApiPreferences.getInstance(context).saveFeatureToggle(FEATURE_KEY, enabled)
    }

    fun agentCountFlow(context: Context): Flow<Int> {
        return context.multiAgentDataStore.data.map { preferences ->
            normalizeAgentCount(preferences[AGENT_COUNT])
        }
    }

    suspend fun getAgentCount(context: Context): Int {
        return agentCountFlow(context).first()
    }

    suspend fun setAgentCount(context: Context, count: Int) {
        val normalized = normalizeAgentCount(count)
        context.multiAgentDataStore.edit { preferences ->
            preferences[AGENT_COUNT] = normalized
        }
    }

    fun discussionRoundsFlow(context: Context): Flow<Int> {
        return context.multiAgentDataStore.data.map { preferences ->
            normalizeDiscussionRounds(preferences[DISCUSSION_ROUNDS])
        }
    }

    suspend fun getDiscussionRounds(context: Context): Int {
        return discussionRoundsFlow(context).first()
    }

    suspend fun setDiscussionRounds(context: Context, rounds: Int) {
        val normalized = normalizeDiscussionRounds(rounds)
        context.multiAgentDataStore.edit { preferences ->
            preferences[DISCUSSION_ROUNDS] = normalized
        }
    }

    private fun normalizeDiscussionRounds(rounds: Int?): Int {
        return (rounds ?: DEFAULT_DISCUSSION_ROUNDS)
            .coerceIn(MIN_DISCUSSION_ROUNDS, MAX_DISCUSSION_ROUNDS)
    }

    /**
     * agent 角色卡 id 映射（agent 编号 -> CharacterCard 实际 id）。
     * createCharacterCard 会用随机 UUID 覆盖传入 id，因此创建后必须记录真实 id。
     */
    private fun readCardIdMap(preferences: Preferences): Map<String, String> {
        val json = preferences[AGENT_CARD_IDS] ?: return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(json)
        }.getOrDefault(emptyMap())
    }

    private fun writeCardIdMap(
        preferences: MutablePreferences,
        mapping: Map<String, String>
    ) {
        preferences[AGENT_CARD_IDS] =
            kotlinx.serialization.json.Json.encodeToString(mapping)
    }

    suspend fun getAgentCardId(context: Context, agentId: String): String? {
        val mapping = context.multiAgentDataStore.data.first().let { readCardIdMap(it) }
        return mapping[agentId]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveAgentCardId(context: Context, agentId: String, cardId: String) {
        context.multiAgentDataStore.edit { preferences ->
            val updated = readCardIdMap(preferences).toMutableMap()
            updated[agentId] = cardId
            writeCardIdMap(preferences, updated)
        }
    }

    private fun normalizeAgentCount(count: Int?): Int {
        return (count ?: DEFAULT_AGENT_COUNT).coerceIn(MIN_AGENTS, MAX_AGENTS)
    }
}

package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.services.core.MultiAgentCollaborationPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import kotlinx.coroutines.launch

/**
 * 多 Agent 协作设置页
 *
 * - 总开关：开启后聊天输入菜单出现“多 Agent 协作”开关（每会话可用）；
 * - agent 数量：2-10 个；
 * - 协作模型：在功能模型配置页为“多 Agent 协作”选择独立配置，或保持默认
 *   跟随主模型（无需额外配置 API）。本页展示当前绑定以便确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentCollaborationSettingsScreen(
    onBackPressed: () -> Unit = {},
    onNavigateToFunctionalConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val functionalConfigManager = remember { FunctionalConfigManager(context) }
    val modelConfigManager = remember { ModelConfigManager(context) }

    val enabled by MultiAgentCollaborationPreferences.isEnabledFlow(context)
        .collectAsState(initial = false)
    val agentCount by MultiAgentCollaborationPreferences.agentCountFlow(context)
        .collectAsState(initial = MultiAgentCollaborationPreferences.DEFAULT_AGENT_COUNT)
    val discussionRounds by MultiAgentCollaborationPreferences.discussionRoundsFlow(context)
        .collectAsState(initial = MultiAgentCollaborationPreferences.DEFAULT_DISCUSSION_ROUNDS)

    var currentConfigSummary by remember { mutableStateOf<ModelConfigSummary?>(null) }
    var configSummaries by remember { mutableStateOf<List<ModelConfigSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCountPicker by remember { mutableStateOf(false) }
    var showRoundsPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        configSummaries = modelConfigManager.getAllConfigSummaries()
        val mapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.MULTI_AGENT)
        currentConfigSummary = configSummaries.find { it.id == mapping.configId }
        isLoading = false
    }

    CustomScaffold() { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // 顶部说明卡片
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.multi_agent_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(id = R.string.multi_agent_settings_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 总开关
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.multi_agent_enable),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(id = R.string.multi_agent_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    MultiAgentCollaborationPreferences.setEnabled(context, checked)
                                }
                            }
                        )
                    }
                }

                // agent 数量
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { showCountPicker = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.multi_agent_agent_count),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(id = R.string.multi_agent_agent_count_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$agentCount",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 讨论轮数
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { showRoundsPicker = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.multi_agent_discussion_rounds),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(id = R.string.multi_agent_discussion_rounds_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$discussionRounds",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 协作模型
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onNavigateToFunctionalConfig() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.multi_agent_model_config_section),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.multi_agent_model_config_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = currentConfigSummary?.name
                                    ?: FunctionalConfigManager.DEFAULT_CONFIG_ID,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(
                                    id = R.string.multi_agent_go_functional_config
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showCountPicker) {
        AlertDialog(
            onDismissRequest = { showCountPicker = false },
            title = { Text(stringResource(id = R.string.multi_agent_agent_count)) },
            text = {
                Column {
                    (MultiAgentCollaborationPreferences.MIN_AGENTS..MultiAgentCollaborationPreferences.MAX_AGENTS)
                        .forEach { count ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            MultiAgentCollaborationPreferences
                                                .setAgentCount(context, count)
                                        }
                                        showCountPicker = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = count == agentCount,
                                    onClick = {
                                        scope.launch {
                                            MultiAgentCollaborationPreferences
                                                .setAgentCount(context, count)
                                        }
                                        showCountPicker = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$count",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountPicker = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }


    if (showRoundsPicker) {
        AlertDialog(
            onDismissRequest = { showRoundsPicker = false },
            title = { Text(stringResource(id = R.string.multi_agent_discussion_rounds)) },
            text = {
                Column {
                    (MultiAgentCollaborationPreferences.MIN_DISCUSSION_ROUNDS..MultiAgentCollaborationPreferences.MAX_DISCUSSION_ROUNDS)
                        .forEach { rounds ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            MultiAgentCollaborationPreferences
                                                .setDiscussionRounds(context, rounds)
                                        }
                                        showRoundsPicker = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = rounds == discussionRounds,
                                    onClick = {
                                        scope.launch {
                                            MultiAgentCollaborationPreferences
                                                .setDiscussionRounds(context, rounds)
                                        }
                                        showRoundsPicker = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$rounds",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoundsPicker = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }
}
// 讨论轮数选择对话框（由 showRoundsPicker 触发，追加在 Composable 函数体之后作为顶层组件）

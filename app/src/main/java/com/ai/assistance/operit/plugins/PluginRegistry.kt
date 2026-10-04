package com.ai.assistance.operit.plugins

import com.ai.assistance.operit.plugins.toolbox.ToolboxPlugin
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgCommonBridgePlugin
import com.ai.assistance.operit.plugins.workflow.WorkflowLifecyclePlugin
import com.ai.assistance.operit.services.core.MultiAgentInputMenuTogglePlugin
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

interface OperitPlugin {
    val id: String

    fun register()
}

object PluginRegistry {
    private val plugins = CopyOnWriteArrayList<OperitPlugin>()
    private val installedPluginIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var builtinsInitialized = false

    @Synchronized
    fun register(plugin: OperitPlugin) {
        plugins.removeAll { it.id == plugin.id }
        plugins.add(plugin)
    }

    @Synchronized
    fun initializeBuiltins() {
        if (builtinsInitialized) return
        builtinsInitialized = true

        register(ToolboxPlugin)
        register(ToolPkgCommonBridgePlugin)
        register(WorkflowLifecyclePlugin)
        installAll()
        // 多 Agent 协作输入菜单开关不属于 OperitPlugin（无宿主生命周期诉求），
        // 只需挂到 InputMenuTogglePluginRegistry 即可，App 启动时注册一次
        MultiAgentInputMenuTogglePlugin.register()
    }

    @Synchronized
    fun installAll() {
        for (plugin in plugins) {
            if (installedPluginIds.add(plugin.id)) {
                plugin.register()
            }
        }
    }
}

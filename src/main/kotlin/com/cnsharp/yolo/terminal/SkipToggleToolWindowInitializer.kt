package com.cnsharp.yolo.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.plugins.terminal.TerminalToolWindowInitializer

/**
 * 把「Skip permissions」勾选框和设置齿轮放到 AI Agents 下拉两侧。
 *
 * 终端前端的 TerminalToolWindowTabsManagerImpl$Initializer 通过
 * toolWindowInitializer 扩展点执行，并调用
 *   setTitleActions([LaunchSelectedAgent, ChevronSelector, AgentSelector])
 * 把下拉放在标题栏右侧（tab actions 才是左侧，之前放错了区域）。
 *
 * 本 initializer 注册在其后（order="last"），因此能拿到已经设置好的状态，
 * 重新调用 setTitleActions 排成：勾选框 → 原下拉三件套 → 设置齿轮。
 */
class SkipToggleToolWindowInitializer : TerminalToolWindowInitializer {

    override fun initialize(toolWindow: ToolWindow) {
        val am = ActionManager.getInstance()
        val skipAction = am.getAction(SKIP_ACTION_ID)
        if (skipAction == null) {
            LOG.warn("AI Agents Extender: action $SKIP_ACTION_ID not found, toggle cannot be installed")
            return
        }

        val agentActions: List<AnAction> = AI_AGENTS_ACTION_IDS.mapNotNull { am.getAction(it) }
        if (agentActions.isEmpty()) {
            LOG.warn("AI Agents Extender: AI Agents actions not found, toggle cannot be installed")
            return
        }

        // 设置齿轮缺失不算致命：下拉和勾选框照常安装，只是少一个入口
        val settingsAction = am.getAction(SETTINGS_ACTION_ID)
        if (settingsAction == null) {
            LOG.warn("AI Agents Extender: action $SETTINGS_ACTION_ID not found, settings button cannot be installed")
        }

        val titleActions = listOf(skipAction) + agentActions + listOfNotNull(settingsAction)
        toolWindow.setTitleActions(titleActions)
        LOG.info("AI Agents Extender: installed terminal title actions (${titleActions.size} items)")
    }

    companion object {
        private val LOG = Logger.getInstance(SkipToggleToolWindowInitializer::class.java)

        private const val SKIP_ACTION_ID = "com.cnsharp.yolo.SkipPermissionsAction"
        private const val SETTINGS_ACTION_ID = "com.cnsharp.yolo.OpenSettingsAction"

        /** AI Agents 下拉的三个 action，顺序与终端前端一致。 */
        private val AI_AGENTS_ACTION_IDS = listOf(
            "Terminal.AiAgents.LaunchSelectedAgent",
            "Terminal.AiAgents.ChevronSelector",
            "Terminal.AiAgents.AgentSelector"
        )
    }
}

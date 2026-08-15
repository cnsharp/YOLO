package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.settings.AgentExtenderSettingsExp
import com.cnsharp.yolo.settings.AgentExtenderSettingsListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.frontend.action.TerminalAgentsAvailabilityService
import org.jetbrains.plugins.terminal.TerminalToolWindowInitializer

/**
 * Place the "Skip permissions" checkbox and the settings gear on either side of the AI Agents dropdown.
 *
 * The terminal frontend's TerminalToolWindowTabsManagerImpl$Initializer runs via the
 * toolWindowInitializer extension point and calls
 *   setTitleActions([LaunchSelectedAgent, ChevronSelector, AgentSelector])
 * to put the dropdown on the right side of the title bar (tab actions are on the left; it was previously in the wrong area).
 *
 * This initializer is registered after it (order="last"), so it can read the already-configured state and
 * re-call setTitleActions to arrange: checkbox -> original dropdown trio -> settings gear.
 */
class SkipToggleToolWindowInitializer : TerminalToolWindowInitializer {

    override fun initialize(toolWindow: ToolWindow) {
        // The terminal tool window is initialized during startup; this hook triggers an agent sync once per IDE
        // session, adding currently installed agents to the config and removing the need for manual "reload". Idempotent; repeated calls are harmless.
        AgentExtenderSettingsExp.getInstance().ensureSyncScheduled()

        subscribeToSettingsChanges(toolWindow)

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

        // A missing settings gear is not fatal: the dropdown and checkbox are still installed, just one entry point short.
        val settingsAction = am.getAction(SETTINGS_ACTION_ID)
        if (settingsAction == null) {
            LOG.warn("AI Agents Extender: action $SETTINGS_ACTION_ID not found, settings button cannot be installed")
        }

        val titleActions = listOf(skipAction) + agentActions + listOfNotNull(settingsAction)
        toolWindow.setTitleActions(titleActions)
        LOG.info("AI Agents Extender: installed terminal title actions (${titleActions.size} items)")
    }

    /**
     * Refresh the AI Agents toolbar when the user applies changes in Settings.
     *
     * Opening the dropdown already calls TerminalAgentsAvailabilityService.refreshAvailableAgents() itself, so
     * the popup contents are never stale. The title-bar buttons, however, read the *cached* list
     * (getAvailableTerminalAgentEntries -> getAvailableAgents), so without this the selected agent's name and
     * icon keep showing pre-Apply values until the dropdown is opened once.
     */
    private fun subscribeToSettingsChanges(toolWindow: ToolWindow) {
        val project = toolWindow.project
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
            .subscribe(AgentExtenderSettingsExp.CHANGED, object : AgentExtenderSettingsListener {
                override fun changed() {
                    TerminalAgentsAvailabilityService.getInstance(project).prewarm()
                }
            })
    }

    companion object {
        private val LOG = Logger.getInstance(SkipToggleToolWindowInitializer::class.java)

        private const val SKIP_ACTION_ID = "com.cnsharp.yolo.exp.SkipPermissionsAction"
        private const val SETTINGS_ACTION_ID = "com.cnsharp.yolo.exp.OpenSettingsAction"

        /** The three actions of the AI Agents dropdown, ordered consistently with the terminal frontend. */
        private val AI_AGENTS_ACTION_IDS = listOf(
            "Terminal.AiAgents.LaunchSelectedAgent",
            "Terminal.AiAgents.ChevronSelector",
            "Terminal.AiAgents.AgentSelector"
        )
    }
}

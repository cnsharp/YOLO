package com.cnsharp.yolo.launcher

import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import javax.swing.Icon

/**
 * Global "Skip permissions" toggle state (stored in Settings).
 *
 * Installed into the terminal title bar by SkipToggleToolWindowInitializer via the toolWindowInitializer
 * extension point, right next to the left of the AI Agents dropdown.
 *
 * The icon is a lowercase y: gray when off, red when on — visually consistent with the built-in
 * checkbox/toggle behavior, but the custom icon lets users immediately recognize this as the "skip permissions" switch.
 */
/* Text is not hardcoded here: the <action> in plugin.xml already declares <resource-bundle>,
 * so the platform reads action.<id>.text / .description from YoloBundle and follows IDE language switching. */
class SkipPermissionsAction : ToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        AgentExtenderSettings.getInstance().state.skipEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentExtenderSettings.getInstance().state.skipEnabled = state
    }

    /** State only reads the in-memory settings and does no PATH probing, so it can be safely evaluated on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** Dynamically switch the icon: off = gray y, on = red y. */
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = if (isSelected(e)) AgentIcons.SKIP_Y_ON else AgentIcons.SKIP_Y_OFF
    }
}

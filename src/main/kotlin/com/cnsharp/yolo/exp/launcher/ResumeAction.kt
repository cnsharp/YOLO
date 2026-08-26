package com.cnsharp.yolo.exp.launcher

import com.cnsharp.yolo.exp.settings.AgentExtenderSettingsExp
import com.cnsharp.yolo.exp.terminal.AgentIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Global "Resume session" toggle state (stored in Settings).
 *
 * Installed into the Terminal tool window title bar by SkipToggleToolWindowInitializer (order="last") via the
 * toolWindowInitializer extension point, next to the "Skip permissions" toggle and left of the AI Agents dropdown.
 *
 * When on, the selected agent is launched with its `resumeFlag` (from agents.json) appended to the command,
 * so the agent resumes the most recent (or a chosen) session. Agents without a resumeFlag are launched normally.
 *
 * The icon is a replay mark: gray when off, green when on — visually consistent with the Skip-permissions toggle.
 */
/* Text is not hardcoded here: the <action> in plugin.xml already declares <resource-bundle>,
 * so the platform reads action.<id>.text / .description from YoloBundle and follows IDE language switching. */
class ResumeAction : ToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        AgentExtenderSettingsExp.getInstance().state.resumeEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentExtenderSettingsExp.getInstance().state.resumeEnabled = state
    }

    /** State only reads the in-memory settings, so it can be safely evaluated on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** Dynamically switch the icon: off = gray replay, on = green replay. */
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = if (isSelected(e)) AgentIcons.RESUME_ON else AgentIcons.RESUME_OFF
    }
}

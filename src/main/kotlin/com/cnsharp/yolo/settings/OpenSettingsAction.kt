package com.cnsharp.yolo.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * The gear button in the YOLO panel header, opening this plugin's settings page with one click.
 *
 * Added to the panel's toolbar by YoloToolWindowFactory.
 * Locate the settings page by the Configurable class rather than its display name: the display name changes
 * with the IDE language, the class does not.
 */
/* The text is not hardcoded here: the <action> in plugin.xml already declares <resource-bundle>,
 * so the platform pulls action.<id>.text / .description from YoloBundle and follows the IDE language switch. */
class OpenSettingsAction : AnAction(AllIcons.General.Settings) {

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, AgentExtenderConfigurable::class.java)
    }

    /** No update logic; the icon and text are static, so evaluating on a background thread is fine. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

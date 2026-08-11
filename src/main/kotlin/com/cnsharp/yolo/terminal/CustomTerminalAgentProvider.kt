package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.CustomTool
import org.jetbrains.plugins.terminal.agent.TerminalAgent
import org.jetbrains.plugins.terminal.agent.TerminalAgentProvider
import javax.swing.Icon

/**
 * Append tools configured by the user in Settings to the terminal "AI Agents" dropdown.
 *
 * This is the extension point the terminal dropdown actually reads
 * (org.jetbrains.plugins.terminal.terminalAgentProvider).
 * DefaultTerminalAgentProvider provides Junie / Claude Code / Codex at order="first";
 * this provider only appends and never replaces the built-in entries.
 */
class CustomTerminalAgentProvider : TerminalAgentProvider {

    override fun getTerminalAgents(): List<TerminalAgent> =
        AgentExtenderSettings.getInstance().state.customTools
            .filter { it.id.isNotBlank() && it.command.isNotBlank() }
            .map { CustomTerminalAgent(it) }
}

private class CustomTerminalAgent(private val tool: CustomTool) : TerminalAgent {

    override val agentKey: TerminalAgent.AgentKey
        get() = TerminalAgent.AgentKey("custom.${tool.id}")

    override val displayName: String
        get() = tool.displayName.ifBlank { tool.id }

    /** The terminal looks up this executable in PATH and known locations; if not found it won't appear in the dropdown. */
    override val binaryName: String
        get() = tool.command

    /** User-specified icon > bundled official icon > generic icon. */
    override val icon: Icon
        get() = AgentIcons.forAgent(tool.id, tool.iconPath)

    /** Matches the built-in Claude Code: also show the icon in the tab. */
    override val showIconInTab: Boolean
        get() = true
}

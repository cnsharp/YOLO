package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.CustomTool
import org.jetbrains.plugins.terminal.agent.TerminalAgent
import org.jetbrains.plugins.terminal.agent.TerminalAgentProvider
import javax.swing.Icon

/**
 * 把用户在 Settings 里配置的工具追加到终端 "AI Agents" 下拉列表。
 *
 * 这是终端下拉真正读取的扩展点（org.jetbrains.plugins.terminal.terminalAgentProvider）。
 * DefaultTerminalAgentProvider 以 order="first" 提供 Junie / Claude Code / Codex，
 * 本 provider 只做追加，不会替换内置项。
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

    /** 终端会在 PATH 与已知位置里查找这个可执行文件；找不到就不会出现在下拉里。 */
    override val binaryName: String
        get() = tool.command

    /** 用户指定图标 > 随包官方图标 > 通用图标。 */
    override val icon: Icon
        get() = AgentIcons.forAgent(tool.id, tool.iconPath)

    /** 与内置的 Claude Code 一致，在标签页上也显示图标。 */
    override val showIconInTab: Boolean
        get() = true
}

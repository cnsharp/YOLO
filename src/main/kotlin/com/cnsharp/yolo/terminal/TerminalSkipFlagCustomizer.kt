package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.util.baseName
import com.cnsharp.yolo.settings.AgentExtenderSettingsExp
import com.cnsharp.yolo.settings.DefaultSkipEnvs
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecCommandImpl
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer

/**
 * The injection point that actually takes effect.
 *
 * The terminal "AI Agents" dropdown does not go through ACP: TerminalAgentResolver.resolveLaunchSpec
 * hardcodes the command as listOf(binaryPath), and the TerminalAgent interface has no parameter slot.
 * LocalTerminalDirectRunner, however, calls the
 * org.jetbrains.plugins.terminal.shellExecOptionsCustomizer extension point right before spawning the process,
 * and allows replacing execCommand entirely — this is the only place where arguments can be appended.
 *
 * This extension point is invoked for every terminal launch (including a plain zsh), so we must match by
 * executable filename exactly; only a configured agent triggers command rewriting.
 */
class TerminalSkipFlagCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, options: MutableShellExecOptions) {
        val command = options.execCommand.command
        if (command.isEmpty()) return

        val exeName = baseName(command[0])
        val settings = AgentExtenderSettingsExp.getInstance()
        val state = settings.state

        // Only treat this as an agent launch when it matches a configured custom tool or permission rule.
        // On Windows filenames are case-insensitive (PATH may contain Claude.CMD), so comparisons ignore case.
        val tool = state.customTools.firstOrNull {
            it.id.equals(exeName, ignoreCase = true) || baseName(it.command).equals(exeName, ignoreCase = true)
        }
        val rule = state.permissionRules.firstOrNull {
            it.agentId.equals(exeName, ignoreCase = true) || baseName(it.agentId).equals(exeName, ignoreCase = true)
        }
        // IDEA's built-in agents are not in customTools, so their base args come from this map instead.
        val builtInBaseArgs = state.agentBaseArgs[exeName.lowercase()]
        if (tool == null && rule == null && builtInBaseArgs.isNullOrBlank()) return

        val extra = mutableListOf<String>()

        // Fixed launch args, from the custom tool if it is one, otherwise from the built-in agent's overrides
        val baseArgs = tool?.baseArgs ?: builtInBaseArgs
        baseArgs?.split(' ')?.filter { it.isNotBlank() }?.let { extra += it }

        // Only append the skip args when the toolbar "Skip permissions" checkbox is on.
        // The flag may be multiple tokens (e.g. cline's "--auto-approve true"), so it must be split into
        // separate argv entries; otherwise it becomes a single space-containing argument that cannot be parsed on any platform.
        if (state.skipEnabled && rule != null) {
            val tokens = rule.flag.split(' ').filter { it.isNotBlank() }
            if (tokens.isNotEmpty() && tokens[0] !in command) extra += tokens
        }

        // A few agents (e.g. goose) have no skip arg and only recognize an env var; it must be set before the
        // process starts, not appended to the command line — otherwise it would just become a positional argument.
        if (state.skipEnabled) {
            DefaultSkipEnvs.forId(exeName)?.let { (name, value) ->
                options.setEnvironmentVariable(name, value)
                LOG.info("AI Agents Extender: agent=$exeName injected env var $name=$value")
            }
        }

        if (extra.isEmpty()) {
            LOG.info("AI Agents Extender: agent=$exeName skipEnabled=${state.skipEnabled} no args injected, command=$command")
            return
        }

        val newCommand = command + extra
        LOG.info("AI Agents Extender: agent=$exeName skipEnabled=${state.skipEnabled} injected=$extra, command=$newCommand")
        options.setExecCommand(ShellExecCommandImpl(newCommand))
    }

    companion object {
        private val LOG = Logger.getInstance(TerminalSkipFlagCustomizer::class.java)
    }
}

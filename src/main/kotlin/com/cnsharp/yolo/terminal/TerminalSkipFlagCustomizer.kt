package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.util.baseName
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.DefaultSkipEnvs
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecCommandImpl
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer

/**
 * 真正生效的注入点。
 *
 * 终端 "AI Agents" 下拉不走 ACP：TerminalAgentResolver.resolveLaunchSpec 把命令
 * 固定构造成 listOf(binaryPath)，TerminalAgent 接口没有任何参数位。
 * 而 LocalTerminalDirectRunner 在真正 spawn 进程前会调用
 * org.jetbrains.plugins.terminal.shellExecOptionsCustomizer 扩展点，
 * 并允许整体替换 execCommand —— 这里就是唯一能追加参数的地方。
 *
 * 该扩展点对所有终端启动都会回调（包括普通 zsh），因此必须按可执行文件名精确匹配，
 * 只有命中用户配置的 agent 才改写命令。
 */
class TerminalSkipFlagCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, options: MutableShellExecOptions) {
        val command = options.execCommand.command
        if (command.isEmpty()) return

        val exeName = baseName(command[0])
        val settings = AgentExtenderSettings.getInstance()
        val state = settings.state

        // 只有命中已配置的自定义工具或权限规则，才认为这是一次 agent 启动。
        // Windows 上文件名大小写不敏感（PATH 里可能是 Claude.CMD），比较一律忽略大小写。
        val tool = state.customTools.firstOrNull {
            it.id.equals(exeName, ignoreCase = true) || baseName(it.command).equals(exeName, ignoreCase = true)
        }
        val rule = state.permissionRules.firstOrNull {
            it.agentId.equals(exeName, ignoreCase = true) || baseName(it.agentId).equals(exeName, ignoreCase = true)
        }
        if (tool == null && rule == null) return

        val extra = mutableListOf<String>()

        // 自定义工具的固定启动参数
        tool?.baseArgs?.split(' ')?.filter { it.isNotBlank() }?.let { extra += it }

        // 工具栏 “Skip permissions” 勾选框打开时，才追加 skip 参数。
        // flag 可能是多个 token（如 cline 的 "--auto-approve true"），必须拆成独立 argv，
        // 否则会变成一个带空格的单参数，任何平台上都解析不出来。
        if (state.skipEnabled && rule != null) {
            val tokens = rule.flag.split(' ').filter { it.isNotBlank() }
            if (tokens.isNotEmpty() && tokens[0] !in command) extra += tokens
        }

        // 少数 agent（如 goose）没有 skip 参数，只认环境变量；必须在进程启动前设置，
        // 不能拼到命令行后面——那样只会变成一个位置参数。
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

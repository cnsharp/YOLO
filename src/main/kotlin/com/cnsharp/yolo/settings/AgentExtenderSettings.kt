package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.plugins.terminal.agent.TerminalAgent

/** 单个工具的「跳过权限」规则：只保存该 agent 的 skip 参数值；
 *  是否真正注入由工具栏全局 “Skip permissions” 勾选框（skipEnabled）决定。 */
data class PermissionRule(
    var agentId: String = "",
    var flag: String = "--dangerously-skip-permissions"
)

/** 一个出现在终端「AI Agents」下拉里的自定义工具（用户追加的，区别于 IDEA 内置 agent）。 */
data class CustomTool(
    var id: String = "",
    var displayName: String = "",
    var command: String = "",
    var baseArgs: String = "",
    /** 下拉里显示的图标，本地文件绝对路径（.svg 最佳，也支持 .png）。留空则按 id 找随包图标，再回退默认。 */
    var iconPath: String = ""
)

/** 各 agent 的 skip-permission 默认值（仅作便利预填，IDEA 本身不暴露此信息）。
 *  不在表中的 agent 不自动预填，由用户在权限规则表手动添加。 */
object DefaultSkipFlags {
    private val map: Map<String, String> = mapOf(
        // IDEA built-in agents
        "junie"     to "--dangerously-skip-permissions",
        "claude"    to "--dangerously-skip-permissions",
        "codex"     to "--yolo",
        // Promoted / custom agents with a launch-time permission-bypass flag
        "codebuddy" to "-y",
        "gemini"    to "--yolo",
        "copilot"   to "--allow-all",
        "cursor-agent" to "--force",
        "kimi"      to "--yolo",
        "qoder"     to "--dangerously-skip-permissions",
        "hermes"    to "--yolo",
        "opencode"  to "--auto",
        "pi"        to "--approve",        // pi: trust project-local files for this run
        "cn"        to "--auto",           // continue binary is 'cn'
        "cline"     to "--auto-approve true",
        // Agents below have no launch-time bypass flag; see DefaultSkipEnvs for env-based ones.
        // kilo: only 'kilo run' accepts --dangerously-skip-permissions; bare TUI does not.
        // openclaw: only persistent config ('openclaw exec-policy preset yolo').
        // pi: --approve trusts project-local files (AGENTS.md/SYSTEM.md/skills) for this run.
    )
    /** Returns the flag string, or empty if no known flag exists for this binary. */
    fun forId(id: String): String = map[id.lowercase()] ?: ""
}

/** Agents whose permission bypass is an environment variable instead of a CLI flag.
 *  An env var must be exported before the process starts — it cannot be appended to
 *  the command line, where it would become a positional argument. */
object DefaultSkipEnvs {
    private val map: Map<String, Pair<String, String>> = mapOf(
        "goose" to ("GOOSE_MODE" to "auto")
    )
    fun forId(id: String): Pair<String, String>? = map[id.lowercase()]
}

/** IDEA 内置支持的 agent：动态取自 TerminalAgentProvider（排除本插件自己的 provider）。
 *  IDEA 升级新增了内置 agent 时，这里会自动跟着变，无需再硬编码清单。 */
object BuiltInAgents {
    fun all(): List<TerminalAgent> =
        TerminalAgent.getAllTerminalAgents()
            .filter { !it.agentKey.key.startsWith("custom.", ignoreCase = true) }
}

/** 本插件额外推广、但 IDEA 未内置的 agent（需写入 customTools 才出现在下拉）。
 *  与 BuiltInAgents 不同：这部分是插件主动提供的便捷项，并非 IDEA 原生支持，
 *  所以仍需硬编码——IDEA 不发布的 agent 我们无法“动态”得知。 */
object PromotedAgents {
    data class Meta(val id: String, val displayName: String, val command: String)
    val entries: List<Meta> = listOf(
        Meta("cline",     "Cline",    "cline"),
        Meta("codebuddy", "CodeBuddy", "codebuddy"),
        Meta("continue",  "Continue", "cn"),
        Meta("copilot",   "Copilot",  "copilot"),
        Meta("cursor",    "Cursor",   "cursor-agent"),
        Meta("gemini",    "Gemini",   "gemini"),
        Meta("goose",     "Goose",    "goose"),
        Meta("hermes",    "Hermes",   "hermes"),
        Meta("kilo",      "Kilo Code","kilo"),
        Meta("kimi",      "Kimi",     "kimi"),
        Meta("openclaw",  "OpenClaw","openclaw"),
        Meta("opencode",  "OpenCode", "opencode"),
        Meta("pi",        "Pi",       "pi"),
        Meta("qoder",     "Qoder",    "qoder")
    )
}

@State(name = "AgentExtenderSettings", storages = [Storage("agentExtender.xml")])
@Service(Service.Level.APP)
class AgentExtenderSettings : PersistentStateComponent<AgentExtenderSettings.State> {

    private var currentState: State = State()

    private var preloadScheduled = false

    init {
        // 首次运行（无已保存配置）时，异步探测已安装 agent 并预加载。
        // 必须在后台线程执行（探测会 spawn 进程），绝不能在 EDT 上做。
        ensurePreloadScheduled()
    }

    /** 若配置为空，调度一次后台探测预加载（仅执行一次）。 */
    fun ensurePreloadScheduled() {
        if (preloadScheduled) return
        preloadScheduled = true
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (currentState.permissionRules.isEmpty() && currentState.customTools.isEmpty()) {
                preloadDetected()
            }
        }
    }

    /** 首次运行（配置为空）时调用：探测 PATH 上已安装的候选 agent，把缺失的条目补进当前配置。
     *  - 权限规则：对所有检测到的 agent（内置 + 推广）都加（需要 skip flag 注入）。
     *  - 自定义工具：仅对“推广 agent”（IDEA 未内置，如 codebuddy）加；内置 agent 由 IDEA 下拉提供，不写 customTools。
     *  注意：会 spawn 进程，调用方需确保在后台线程执行。 */
    fun preloadDetected() {
        // IDEA 内置 agent：命中 PATH 则补权限规则（不写 customTools）。
        // 注意：规则 agentId 必须用 binaryName（如 "claude"），而不是 IDEA 的 agentKey（"claude_code"）——
        // TerminalSkipFlagCustomizer 是按可执行文件名匹配的，用 agentKey 会永远匹配不上、skip 参数不注入。
        for (agent in BuiltInAgents.all()) {
            val id = agent.binaryName
            if (!AgentDetector.isOnPath(id)) continue
            if (currentState.permissionRules.none { it.agentId == id }) {
                currentState.permissionRules.add(PermissionRule(id, DefaultSkipFlags.forId(id)))
            }
        }
        // 插件推广 agent（IDEA 未内置）：命中 PATH 则补权限规则 + 加入 customTools。
        // 规则 key 与 flag 都必须用 command（可执行文件名）而不是 id：
        // cursor 的命令是 cursor-agent、continue 的命令是 cn，用 id 会既查不到默认 flag、
        // 也永远匹配不上 TerminalSkipFlagCustomizer（它按可执行文件名匹配）。
        for ((id, displayName, command) in PromotedAgents.entries) {
            if (!AgentDetector.isOnPath(command)) continue
            if (currentState.permissionRules.none { it.agentId == command }) {
                currentState.permissionRules.add(PermissionRule(command, DefaultSkipFlags.forId(command)))
            }
            if (currentState.customTools.none { it.id == id }) {
                currentState.customTools.add(
                    CustomTool(id = id, displayName = displayName, command = command)
                )
            }
        }
    }

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, currentState)
    }

    class State {
        // 工具栏全局 “Skip permissions” 勾选框状态；默认关闭，必须用户主动勾选才注入。
        var skipEnabled: Boolean = false
        // 各 agent 的 skip 参数值（来自 Settings）；是否注入由 skipEnabled 控制。
        var permissionRules: MutableList<PermissionRule> = mutableListOf()
        var customTools: MutableList<CustomTool> = mutableListOf()
    }

    companion object {
        fun getInstance(): AgentExtenderSettings =
            com.intellij.openapi.components.service<AgentExtenderSettings>()
                .also { it.ensurePreloadScheduled() }
    }
}

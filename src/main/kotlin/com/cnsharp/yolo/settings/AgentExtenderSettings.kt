package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** "Skip permissions" rule for a single tool: only stores this agent's skip flag value;
 *  whether it is actually injected is decided by the toolbar's global "Skip permissions" checkbox (skipEnabled). */
data class PermissionRule(
    var agentId: String = "",
    var flag: String = "--dangerously-skip-permissions"
)

/** A custom tool that appears in the terminal "AI Agents" dropdown (user-added, distinct from IDEA's built-in agents). */
data class CustomTool(
    var id: String = "",
    var displayName: String = "",
    var command: String = "",
    var baseArgs: String = "",
    /** Icon shown in the dropdown: absolute path to a local file (.svg preferred, .png also supported). If blank, an icon bundled with the package is looked up by id, falling back to a default. */
    var iconPath: String = ""
)

/** Default skip-permission flags per agent (convenience prefill only; IDEA itself does not expose this info).
 *  Agents not in the table are not auto-prefilled; the user adds them manually in the permission rules table. */
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

/** Agents promoted by this plugin (proactively offered, listed at the top of the panel/settings).
 *  These are convenience entries offered by the plugin, not natively supported by IDEA, so they are
 *  hardcoded. List order is the display order — Claude Code and Codex are pinned as the Top 2. */
object PromotedAgents {
    data class Meta(val id: String, val displayName: String, val command: String)
    val entries: List<Meta> = listOf(
        Meta("claude",    "Claude Code", "claude"),
        Meta("codex",     "Codex",     "codex"),
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

    private var syncScheduled = false

    init {
        // On every IDE startup (first access to the service, i.e. one session), trigger one background sync:
        // add the currently "installed" built-in / promoted agents into the config so the terminal dropdown
        // and settings panel reflect the latest install state.
        // The old logic only probed once when the config was empty, so agents installed after first run (e.g. codebuddy)
        // could never get in and you had to clear the config and rerun — which is exactly why "reload on every startup" is needed.
        // Must run on a background thread (the probe spawns processes); never on the EDT.
        ensureSyncScheduled()
    }

    /** Schedule a background sync (once only; a flag guarantees idempotency, so repeated calls are harmless). */
    fun ensureSyncScheduled() {
        if (syncScheduled) return
        syncScheduled = true
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            syncInstalledAgents()
        }
    }

    /**
     * Sync currently-installed agents into the config; called once per startup.
     *  - Promoted agents (e.g. claude/codex/codebuddy, not built into IDEA): if detected as installed, add a permission rule
     *    AND add to customTools so it appears in the YOLO panel.
     *  All are "skip if present" — idempotent and never deletes existing entries, so running once per startup is safe.
     *  Note: spawns processes, so the caller must ensure this runs on a background thread.
     */
    fun syncInstalledAgents() {
        for ((id, displayName, command) in PromotedAgents.entries) {
            if (!AgentDetector.canExecute(command)) continue
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
        // Toolbar global "Skip permissions" checkbox state; off by default, only injected when the user explicitly checks it.
        var skipEnabled: Boolean = false
        // Each agent's skip flag value (from Settings); whether it is injected is controlled by skipEnabled.
        var permissionRules: MutableList<PermissionRule> = mutableListOf()
        var customTools: MutableList<CustomTool> = mutableListOf()
        /**
         * Cache of commands (lower-cased) detected as installed on the machine. Persisted so the dropdown
         * loads instantly from this cache on the next open; a background re-scan refreshes it and only
         * touches the dropdown when the detected set actually differs.
         */
        var installedCommands: MutableList<String> = mutableListOf()
    }

    companion object {
        fun getInstance(): AgentExtenderSettings =
            com.intellij.openapi.components.service<AgentExtenderSettings>()
                .also { it.ensureSyncScheduled() }
    }
}

package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.plugins.terminal.agent.TerminalAgent

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

/** IDEA's built-in agents: dynamically taken from TerminalAgentProvider (excluding this plugin's own provider).
 *  When an IDEA upgrade adds a new built-in agent, this updates automatically — no need to hardcode a list. */
object BuiltInAgents {
    fun all(): List<TerminalAgent> =
        TerminalAgent.getAllTerminalAgents()
            .filter { !it.agentKey.key.startsWith("custom.", ignoreCase = true) }
}

// Namespaced as "AgentExtenderSettingsExp" so this plugin (com.cnsharp.yolo.exp) can be installed
// alongside the release build (com.cnsharp.yolo, v1.0.1) without a "Conflicting component name" error.
// Both register a PersistentStateComponent, so the @State name AND the storage file must differ.
@State(name = "AgentExtenderSettingsExp", storages = [Storage("agentExtenderExp.xml")])
@Service(Service.Level.APP)
class AgentExtenderSettingsExp : PersistentStateComponent<AgentExtenderSettingsExp.State> {

    private var currentState: State = State()

    private var syncScheduled = false

    /**
     * Lower-cased identifiers (binary name + agent key, e.g. "claude", "claude-code", "codex") of every
     * IDEA built-in agent. Cached during [syncInstalledAgents] so [CustomTerminalAgentProvider] can drop
     * custom tools that duplicate a built-in without re-invoking TerminalAgent.getAllTerminalAgents()
     * (which would recurse, since that call drives the provider itself).
     */
    @Volatile
    private var cachedBuiltInIds: Set<String> = emptySet()

    /** Read-only view of [cachedBuiltInIds] for the terminal agent provider. */
    fun builtInAgentIds(): Set<String> = cachedBuiltInIds

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
     *  - Built-in agents: if detected as installed, add a permission rule (do not write customTools).
     *  - Promoted agents (e.g. codebuddy, not built into IDEA): if detected as installed, add a permission rule
     *    AND add to customTools so it appears in the terminal dropdown.
     *  All are "skip if present" — idempotent and never deletes existing entries, so running once per startup is safe.
     *  Note: spawns processes, so the caller must ensure this runs on a background thread.
     */
    fun syncInstalledAgents() {
        val builtInIds = BuiltInAgents.all().flatMap { agent ->
            listOfNotNull(agent.binaryName, agent.agentKey.key).map { it.lowercase() }
        }.toSet()
        cachedBuiltInIds = builtInIds
        for (def in AgentRegistry.agents) {
            if (!AgentDetector.canExecute(def.command)) continue
            if (currentState.permissionRules.none { it.agentId == def.command }) {
                currentState.permissionRules.add(PermissionRule(def.command, def.skipFlag))
            }
            if (currentState.customTools.none { it.id == def.id }) {
                currentState.customTools.add(
                    CustomTool(id = def.id, displayName = def.displayName, command = def.command)
                )
            }
        }
        currentState.customTools.removeIf { tool ->
            tool.id.lowercase() in builtInIds || tool.command.lowercase() in builtInIds
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
         * Extra launch arguments (base args) for IDEA's built-in agents, keyed by lower-cased command binary
         * name (e.g. `claude`, not the agent key `claude-code`). Built-in agents are deliberately NOT written
         * into [customTools] — without this map, an edit to a built-in agent's "Base args" column in Settings
         * would be silently dropped on save. Promoted and user-added tools store their base args on the
         * [CustomTool] itself.
         *
         * Keyed by binary name because [com.cnsharp.yolo.terminal.TerminalSkipFlagCustomizer] can only match
         * the launched process by its executable filename.
         */
        var agentBaseArgs: MutableMap<String, String> = mutableMapOf()
        /**
         * Cache of commands (lower-cased) detected as installed on the machine. Persisted so the settings page
         * renders install status instantly from this cache on open; a background re-scan refreshes it and only
         * repaints when the detected set actually differs.
         */
        var installedCommands: MutableList<String> = mutableListOf()
    }

    companion object {
        /** Fired on Settings | Tools | YOLO → Apply, so the terminal's AI Agents toolbar can refresh its cached agent list. */
        // Namespaced topic name so it does not collide with the release build's "AgentExtenderSettings.Changed" bus topic.
        val CHANGED: Topic<AgentExtenderSettingsListener> =
            Topic.create("AgentExtenderSettingsExp.Changed", AgentExtenderSettingsListener::class.java)

        fun getInstance(): AgentExtenderSettingsExp =
            com.intellij.openapi.components.service<AgentExtenderSettingsExp>()
                .also { it.ensureSyncScheduled() }
    }

    /** Publish a change so subscribers (the terminal tool window) can refresh from current state. */
    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).changed()
    }
}

/** Notified when the user applies changes in Settings | Tools | YOLO. */
interface AgentExtenderSettingsListener {
    fun changed()
}

package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager

/**
 * Shared "installed agents" cache used by the Settings page to render install status.
 *
 * The cache ([AgentExtenderSettingsExp.State.installedCommands]) is a machine-wide, lower-cased set of commands
 * currently resolvable on PATH. It is persisted so the UI can render instantly on open without spawning a
 * probe; [rescan] re-detects the given commands on a background thread, updates the cache, and notifies the
 * caller only when the detected set actually changed.
 */
object InstalledAgents {

    /** Lower-cased commands currently cached as installed. Cheap read; safe on any thread. */
    fun installed(): Set<String> =
        AgentExtenderSettingsExp.getInstance().state.installedCommands.toSet()

    /**
     * Re-detect [commands] for presence on PATH on a background thread, persist the result into the shared
     * cache, and invoke [onChanged] on the EDT **only when** the detected set differs from the cache — with
     * the up-to-date installed set.
     */
    fun rescan(commands: List<String>, onChanged: (Set<String>) -> Unit) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val detected = commands.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .filter { AgentDetector.canExecute(it) }
                .map { it.lowercase() }
                .toSet()
            app.invokeLater {
                val state = AgentExtenderSettingsExp.getInstance().state
                if (detected == state.installedCommands.toSet()) return@invokeLater
                state.installedCommands.clear()
                state.installedCommands.addAll(detected.sorted())
                onChanged(state.installedCommands.toSet())
            }
        }
    }
}

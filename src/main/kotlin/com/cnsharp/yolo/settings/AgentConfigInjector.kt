package com.cnsharp.yolo.settings

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Facade over the config-file [AgentLlmInjector] implementations. Writes/merges each agent's on-disk config
 * to point at a custom LLM backend. The API key is never written in plaintext: the config references an env
 * var, and the real secret is injected into the spawned process env (resolved from PasswordSafe) — see the
 * return value of [applyConfig].
 *
 * All writes are self-cleaning via the `yolo-` id convention owned by each injector.
 */
object AgentConfigInjector {

    /** Test hook: when set, config files are written under this directory instead of the real user home. */
    internal var testHomeOverride: Path? = null
    private fun home(): Path = testHomeOverride ?: Paths.get(System.getProperty("user.home"))

    fun configBased(command: String): Boolean = LlmProviderSupport.isConfigBased(command)

    /**
     * Apply [provider] to the config file for [command]. Returns the env vars (api-key carriers) that must be
     * injected into the spawned process so the config's `${ENV}` / `{env:...}` / `env_key` references resolve.
     */
    fun applyConfig(provider: LlmProvider, command: String): Map<String, String> {
        val inj = LlmInjectorRegistry.byCommand(command) ?: return emptyMap()
        return inj.applyConfig(provider, home())
    }

    /** Remove all YOLO-managed entries for [command] (call when a provider is unbound). */
    fun removeAll(command: String) {
        LlmInjectorRegistry.byCommand(command)?.removeConfig(home())
    }
}

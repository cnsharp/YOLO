package com.cnsharp.yolo.settings

/**
 * Facade over the per-agent [AgentLlmInjector] implementations (see [AgentLlmInjectors]). Keeps the
 * public surface the UI/launch code already calls (toEnv / isConfigBased / injectionNote / …) while the
 * actual per-agent logic lives in its own class for maintainability.
 */
object LlmProviderSupport {

    /** Env var names we inject to carry the real key for each config-file agent (referenced by the config). */
    const val CODEBUDDY_API_KEY_ENV = "YOLO_CODEBUDDY_API_KEY"
    const val OPENCODE_API_KEY_ENV = "YOLO_OPENCODE_API_KEY"
    const val CODEX_API_KEY_ENV = "YOLO_CODEX_API_KEY"
    const val KILO_API_KEY_ENV = "YOLO_KILO_API_KEY"
    const val KIMI_API_KEY_ENV = "YOLO_KIMI_API_KEY"
    const val OPENCLAW_API_KEY_ENV = "YOLO_OPENCLAW_API_KEY"
    const val PI_API_KEY_ENV = "YOLO_PI_API_KEY"
    const val CONTINUE_API_KEY_ENV = "YOLO_CONTINUE_API_KEY"
    const val CLINE_API_KEY_ENV = "YOLO_CLINE_API_KEY"

    /**
     * Lower-cased agent commands that are proxy-able open agents (can be pointed at a custom LLM backend).
     * Sourced from the injector registry so the two never drift apart.
     */
    val SUPPORTS_CUSTOM_LLM: Set<String> = LlmInjectorRegistry.all.flatMap { listOf(it.command) + it.aliases }.toSet()

    /** Whether the agent's backend is configured via a config file (vs spawn env). */
    fun isConfigBased(command: String): Boolean = LlmInjectorRegistry.byCommand(command)?.configBased ?: false

    /**
     * Whether [command] may be bound to a custom LLM provider: it must be a proxy-able agent AND currently
     * installed on this machine.
     */
    fun isProviderConfigurable(command: String, installedCommands: Collection<String>): Boolean {
        val cmd = command.lowercase()
        return SUPPORTS_CUSTOM_LLM.contains(cmd) && installedCommands.contains(cmd)
    }

    /**
     * Env-var overrides to inject when launching an **env-based** agent with [provider]. For config-file
     * agents this delegates to an empty map (use [AgentConfigInjector.applyConfig] instead). Explicit
     * [LlmProvider.envOverrides] always win / supplement.
     */
    fun toEnv(provider: LlmProvider, command: String): Map<String, String> {
        val inj = LlmInjectorRegistry.byCommand(command) ?: return emptyMap()
        val map = LinkedHashMap(inj.toEnv(provider))
        map.putAll(provider.envOverrides)
        return map
    }

    /** Human-readable note for the provider dialog, or null if the agent is fully env-configurable. */
    fun injectionNote(command: String): String? = LlmInjectorRegistry.byCommand(command)?.injectionNote()

    /** Stable `yolo-` provider id written into each agent's config (prefixed so we can upsert/remove safely). */
    fun configProviderId(provider: LlmProvider): String = llmConfigProviderId(provider)

    fun familyToVendor(family: ProviderFamily): String = llmFamilyToVendor(family)
}

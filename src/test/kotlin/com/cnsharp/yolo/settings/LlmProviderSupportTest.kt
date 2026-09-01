package com.cnsharp.yolo.settings

import org.junit.Assert.*
import org.junit.Test

class LlmProviderSupportTest {

    @Test
    fun `claude reads ANTHROPIC_ env vars regardless of provider family`() {
        val p = LlmProvider(
            id = "p1", name = "Bedrock", family = ProviderFamily.ANTHROPIC,
            baseUrl = "https://b.example.com", defaultModel = "claude-sonnet-4"
        )
        val env = LlmProviderSupport.toEnv(p, "claude")
        assertEquals("https://b.example.com", env["ANTHROPIC_BASE_URL"])
        assertEquals("claude-sonnet-4", env["ANTHROPIC_MODEL"])
        assertFalse(env.containsKey("ANTHROPIC_API_KEY")) // no key configured
        assertFalse(env.containsKey("OPENAI_BASE_URL"))   // never leaks to other families
    }

    @Test
    fun `goose uses GOOSE_PROVIDER__HOST and GOOSE_PROVIDER__API_KEY schema`() {
        val p = LlmProvider(
            id = "p2", name = "Relay", family = ProviderFamily.OPENAI,
            baseUrl = "https://r.example.com/v1", defaultModel = "gpt-5"
        )
        val env = LlmProviderSupport.toEnv(p, "goose")
        assertEquals("openai", env["GOOSE_PROVIDER"])
        assertEquals("gpt-5", env["GOOSE_MODEL"])
        assertEquals("https://r.example.com/v1", env["GOOSE_PROVIDER__HOST"])
        assertFalse(env.containsKey("OPENAI_BASE_URL")) // goose does NOT read OPENAI_*
    }

    @Test
    fun `goose provider name follows the provider family`() {
        assertEquals("anthropic", LlmProviderSupport.toEnv(
            LlmProvider(family = ProviderFamily.ANTHROPIC), "goose")["GOOSE_PROVIDER"])
        assertEquals("gemini", LlmProviderSupport.toEnv(
            LlmProvider(family = ProviderFamily.GEMINI), "goose")["GOOSE_PROVIDER"])
        // CUSTOM leaves GOOSE_PROVIDER unset (user supplies it via envOverrides).
        assertFalse(LlmProviderSupport.toEnv(
            LlmProvider(family = ProviderFamily.CUSTOM), "goose").containsKey("GOOSE_PROVIDER"))
    }

    @Test
    fun `aider openai family maps to AIDER_OPENAI_ vars`() {
        val p = LlmProvider(
            id = "p3", name = "Azure", family = ProviderFamily.OPENAI,
            baseUrl = "https://a.example.com", defaultModel = "gpt-5"
        )
        val env = LlmProviderSupport.toEnv(p, "aider")
        assertEquals("gpt-5", env["AIDER_MODEL"])
        assertEquals("https://a.example.com", env["AIDER_OPENAI_API_BASE"])
        assertFalse(env.containsKey("AIDER_OPENAI_API_KEY")) // no key configured
    }

    @Test
    fun `aider anthropic family does not leak into openai vars`() {
        val p = LlmProvider(id = "p4", name = "Claude", family = ProviderFamily.ANTHROPIC)
        val env = LlmProviderSupport.toEnv(p, "aider")
        // With no key configured, no key var is emitted; and the openai-family var must never appear.
        assertFalse(env.containsKey("AIDER_OPENAI_API_KEY"))
        assertFalse(env.containsKey("AIDER_ANTHROPIC_API_KEY"))
    }

    @Test
    fun `gemini cli only reads GEMINI_API_KEY via env vars`() {
        val p = LlmProvider(
            id = "p5", name = "Gem", family = ProviderFamily.GEMINI,
            baseUrl = "https://g.example.com", defaultModel = "gemini-2"
        )
        val env = LlmProviderSupport.toEnv(p, "gemini-cli")
        // base url and model are NOT env-injectable for Gemini CLI.
        assertFalse(env.containsKey("GEMINI_BASE_URL"))
        assertFalse(env.containsKey("GEMINI_MODEL"))
    }

    @Test
    fun `codex only reads CODEX_API_KEY via env`() {
        val p = LlmProvider(
            id = "p6", name = "Cx", family = ProviderFamily.OPENAI,
            baseUrl = "https://c.example.com", defaultModel = "gpt-5-codex"
        )
        val env = LlmProviderSupport.toEnv(p, "codex")
        assertFalse(env.containsKey("OPENAI_BASE_URL"))
        assertFalse(env.containsKey("CODEX_MODEL"))
        assertEquals(0, env.size) // no key configured → nothing emitted
    }

    @Test
    fun `opencode reads family key via env, no base url`() {
        val p = LlmProvider(id = "p7", name = "OC", family = ProviderFamily.ANTHROPIC,
            baseUrl = "https://oc.example.com")
        val env = LlmProviderSupport.toEnv(p, "opencode")
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
        assertEquals(0, env.size) // no key configured
    }

    @Test
    fun `custom family only emits explicit envOverrides`() {
        val p = LlmProvider(id = "p8", name = "Raw", family = ProviderFamily.CUSTOM)
        p.envOverrides["X_CUSTOM_HEADER"] = "v1"
        val env = LlmProviderSupport.toEnv(p, "goose")
        assertEquals(1, env.size)
        assertEquals("v1", env["X_CUSTOM_HEADER"])
    }

    @Test
    fun `blank fields are not emitted`() {
        val p = LlmProvider(id = "p9", name = "Empty", family = ProviderFamily.ANTHROPIC)
        assertTrue(LlmProviderSupport.toEnv(p, "claude").isEmpty())
    }

    @Test
    fun `explicit envOverrides always win and supplement`() {
        val p = LlmProvider(id = "p10", name = "Mix", family = ProviderFamily.ANTHROPIC,
            baseUrl = "https://b.example.com")
        p.envOverrides["ANTHROPIC_BASE_URL"] = "https://override.example.com"
        val env = LlmProviderSupport.toEnv(p, "claude")
        assertEquals("https://override.example.com", env["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun `isProviderConfigurable honors allowlist and installed set`() {
        val installed = listOf("claude", "codex", "goose")
        assertTrue(LlmProviderSupport.isProviderConfigurable("claude", installed))
        assertTrue(LlmProviderSupport.isProviderConfigurable("CODEX", installed)) // lower-cased
        assertFalse(LlmProviderSupport.isProviderConfigurable("cursor", installed)) // not allowlisted
        assertFalse(LlmProviderSupport.isProviderConfigurable("goose", listOf("claude"))) // not installed
    }

    @Test
    fun `copilot uses COPILOT_PROVIDER_ env vars`() {
        val p = LlmProvider(
            id = "p11", name = "CP", family = ProviderFamily.OPENAI,
            baseUrl = "https://cp.example.com/v1", defaultModel = "gpt-5"
        )
        val env = LlmProviderSupport.toEnv(p, "copilot")
        assertEquals("https://cp.example.com/v1", env["COPILOT_PROVIDER_BASE_URL"])
        assertEquals("openai", env["COPILOT_PROVIDER_TYPE"])
        assertEquals("gpt-5", env["COPILOT_MODEL"])
        assertFalse(env.containsKey("COPILOT_PROVIDER_API_KEY")) // no key configured
    }

    @Test
    fun `trae uses family-specific base and key env vars`() {
        val openai = LlmProvider(family = ProviderFamily.OPENAI, baseUrl = "https://t.example.com/v1")
        val a = LlmProviderSupport.toEnv(openai, "trae")
        assertEquals("https://t.example.com/v1", a["OPENAI_BASE_URL"])
        assertFalse(a.containsKey("ANTHROPIC_BASE_URL"))

        val anthropic = LlmProvider(family = ProviderFamily.ANTHROPIC, baseUrl = "https://t2.example.com")
        val b = LlmProviderSupport.toEnv(anthropic, "trae")
        assertEquals("https://t2.example.com", b["ANTHROPIC_BASE_URL"])
        assertFalse(b.containsKey("OPENAI_BASE_URL"))
    }

    @Test
    fun `hermes uses OPENAI_ env vars and HERMES_MODEL`() {
        val p = LlmProvider(
            id = "p12", name = "H", family = ProviderFamily.OPENAI,
            baseUrl = "https://h.example.com/v1", defaultModel = "llama-3"
        )
        val env = LlmProviderSupport.toEnv(p, "hermes")
        assertEquals("https://h.example.com/v1", env["OPENAI_BASE_URL"])
        assertEquals("llama-3", env["HERMES_MODEL"])
        assertFalse(env.containsKey("OPENAI_API_KEY")) // no key configured
    }

    @Test
    fun `injectionNote flags agents that need a config file for base url`() {
        assertNull(LlmProviderSupport.injectionNote("claude"))
        assertNull(LlmProviderSupport.injectionNote("goose"))
        assertNull(LlmProviderSupport.injectionNote("aider"))
        assertNotNull(LlmProviderSupport.injectionNote("gemini-cli"))
        assertNotNull(LlmProviderSupport.injectionNote("codex"))
        assertNotNull(LlmProviderSupport.injectionNote("opencode"))
        assertNotNull(LlmProviderSupport.injectionNote("cline"))
        assertNotNull(LlmProviderSupport.injectionNote("kimi"))
        assertNull(LlmProviderSupport.injectionNote("copilot")) // env-based → null
        assertNull(LlmProviderSupport.injectionNote("trae"))
        assertNull(LlmProviderSupport.injectionNote("hermes"))
    }
}

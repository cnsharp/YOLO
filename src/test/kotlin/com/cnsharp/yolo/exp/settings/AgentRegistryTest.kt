package com.cnsharp.yolo.exp.settings

import org.junit.Assert.*
import org.junit.Test

class AgentRegistryTest {

    @Test
    fun `all 18 expected agents are loaded`() {
        assertEquals(18, AgentRegistry.agents.size)
    }

    @Test
    fun `claude has correct attributes`() {
        val claude = AgentRegistry.byId("claude")!!
        assertEquals("Claude Code", claude.displayName)
        assertEquals("claude", claude.command)
        assertEquals("--dangerously-skip-permissions", claude.skipFlag)
        assertEquals("/icons/agents/claude.png", claude.icon)
        assertNull(claude.skipEnv)
    }

    @Test
    fun `goose has skipEnv and empty skipFlag`() {
        val goose = AgentRegistry.byId("goose")!!
        assertEquals("", goose.skipFlag)
        assertEquals("GOOSE_MODE" to "auto", goose.skipEnv)
    }

    @Test
    fun `cursor lookup by command cursor-agent`() {
        val def = AgentRegistry.byCommand("cursor-agent")!!
        assertEquals("cursor", def.id)
        assertEquals("--force", def.skipFlag)
    }

    @Test
    fun `continue lookup by command cn`() {
        val def = AgentRegistry.byCommand("cn")!!
        assertEquals("continue", def.id)
    }

    @Test
    fun `skipFlagFor resolves by command and by id`() {
        assertEquals("--dangerously-skip-permissions", AgentRegistry.skipFlagFor("claude"))
        assertEquals("--force", AgentRegistry.skipFlagFor("cursor-agent"))
        assertEquals("--auto", AgentRegistry.skipFlagFor("cn"))
    }

    @Test
    fun `resumeFlagFor prefers -r and resolves by command and by id`() {
        assertEquals("-r", AgentRegistry.resumeFlagFor("claude"))
        assertEquals("-r", AgentRegistry.resumeFlagFor("codebuddy"))
        assertEquals("--resume", AgentRegistry.resumeFlagFor("cursor-agent"))
        assertEquals("--taskId", AgentRegistry.resumeFlagFor("cline"))
        assertEquals("", AgentRegistry.resumeFlagFor("kilo"))
        assertEquals("", AgentRegistry.resumeFlagFor("cn"))
    }

    @Test
    fun `skipEnvFor returns null for non-env agents`() {
        assertNull(AgentRegistry.skipEnvFor("claude"))
    }

    @Test
    fun `skipEnvFor returns pair for goose`() {
        assertEquals("GOOSE_MODE" to "auto", AgentRegistry.skipEnvFor("goose"))
    }

    @Test
    fun `iconFor returns classpath path for known agent`() {
        assertEquals("/icons/agents/claude.png", AgentRegistry.iconFor("claude"))
    }

    @Test
    fun `iconFor returns null for unknown id`() {
        assertNull(AgentRegistry.iconFor("nonexistent"))
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(AgentRegistry.byId("nonexistent"))
    }

    @Test
    fun `claude and codex are first two entries`() {
        assertEquals("claude", AgentRegistry.agents[0].id)
        assertEquals("codex", AgentRegistry.agents[1].id)
    }

    @Test
    fun `lookup is case-insensitive`() {
        assertNotNull(AgentRegistry.byId("CLAUDE"))
        assertNotNull(AgentRegistry.byCommand("CURSOR-AGENT"))
    }
}

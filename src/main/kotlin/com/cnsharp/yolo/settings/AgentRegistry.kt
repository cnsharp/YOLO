package com.cnsharp.yolo.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger

data class AgentDef(
    val id: String,
    val displayName: String,
    val command: String,
    val skipFlag: String = "",
    val resumeFlag: String = "",
    val skipEnv: Pair<String, String>? = null,
    val icon: String = ""
)

object AgentRegistry {

    private val LOG = Logger.getInstance(AgentRegistry::class.java)

    val agents: List<AgentDef> = load()

    private val byIdMap: Map<String, AgentDef> = agents.associateBy { it.id.lowercase() }
    private val byCommandMap: Map<String, AgentDef> = agents.associateBy { it.command.lowercase() }

    fun byId(id: String): AgentDef? = byIdMap[id.lowercase()]

    fun byCommand(command: String): AgentDef? = byCommandMap[command.lowercase()]

    private fun lookupByKey(key: String): AgentDef? {
        val k = key.lowercase()
        return byCommandMap[k] ?: byIdMap[k]
    }

    /** Lookup by command first, then by id — mirrors how DefaultSkipFlags.forId was called. */
    fun skipFlagFor(key: String): String = lookupByKey(key)?.skipFlag ?: ""

    fun resumeFlagFor(key: String): String = lookupByKey(key)?.resumeFlag ?: ""

    fun skipEnvFor(key: String): Pair<String, String>? = lookupByKey(key)?.skipEnv

    /** Returns the classpath icon path, or null if the agent is unknown or has no icon. */
    fun iconFor(id: String): String? = byIdMap[id.lowercase()]?.icon?.takeIf { it.isNotBlank() }

    private fun load(): List<AgentDef> {
        return try {
            val stream = AgentRegistry::class.java.getResourceAsStream("/agents.json")
            if (stream == null) {
                LOG.error("AgentRegistry: agents.json not found in classpath")
                return emptyList()
            }
            stream.bufferedReader().use { reader ->
                val raw: List<AgentDefJson> = Gson().fromJson(
                    reader,
                    object : TypeToken<List<AgentDefJson>>() {}.type
                )
                raw.map { it.toAgentDef() }
            }
        } catch (e: Exception) {
            LOG.error("AgentRegistry: failed to load agents.json", e)
            emptyList()
        }
    }

    private data class AgentDefJson(
        val id: String = "",
        val displayName: String = "",
        val command: String = "",
        val skipFlag: String = "",
        val resumeFlag: String = "",
        val skipEnv: SkipEnvJson? = null,
        val icon: String = ""
    ) {
        fun toAgentDef() = AgentDef(
            id = id,
            displayName = displayName,
            command = command,
            skipFlag = skipFlag,
            resumeFlag = resumeFlag,
            skipEnv = skipEnv?.let { it.name to it.value },
            icon = icon
        )
    }

    private data class SkipEnvJson(val name: String = "", val value: String = "")
}

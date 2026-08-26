package com.cnsharp.yolo.exp.terminal

import com.cnsharp.yolo.exp.settings.AgentRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import java.io.File
import javax.swing.Icon

/**
 * Icons displayed for custom tools in the dropdown list.
 *
 * Three-level fallback; failure at any level will not make the dropdown entry disappear:
 *   1. Icon file specified by the user in Settings (iconPath, local absolute path)
 *   2. Official icon bundled with the plugin (looked up from AgentRegistry by agent id)
 *   3. Generic icon DEFAULT
 *
 * Bundled icon naming follows the terminal built-in convention: `<name>.svg` + `<name>_dark.svg`,
 * IconLoader automatically selects the _dark variant based on the current theme. Uniform size 16x16.
 */
object AgentIcons {

    private val LOG = Logger.getInstance(AgentIcons::class.java)

    /** Standard edge length for dropdown icons, aligned with built-in claude-code.svg / codex.svg. */
    private const val SIZE = 16


    /** y icon for the Skip permissions checkbox (off/on). */
    val SKIP_Y_OFF: Icon by lazy { loadBundled("/icons/skipY.svg") }
    val SKIP_Y_ON: Icon by lazy { loadBundled("/icons/skipYOn.svg") }

    /** Replay icon for the Resume-session checkbox (off/on). */
    val RESUME_OFF: Icon by lazy { loadBundled("/icons/resume.svg") }
    val RESUME_ON: Icon by lazy { loadBundled("/icons/resumeOn.svg") }

    /** Used by custom tools that have no dedicated icon. */
    val DEFAULT: Icon = AllIcons.Actions.Lightning

    /** Cache key includes iconPath, so changing the path naturally triggers a reload. */
    private val cache = HashMap<String, Icon>()

    /**
     * Get the icon for a tool.
     *
     * @param agentId  tool id, used to match the bundled official icon
     * @param iconPath absolute path of the icon file specified by the user; empty means unspecified
     */
    @Synchronized
    fun forAgent(agentId: String, iconPath: String = ""): Icon {
        val key = "${agentId.lowercase()}|$iconPath"
        cache[key]?.let { return it }

        val icon = loadUserIcon(iconPath)
            ?: AgentRegistry.iconFor(agentId)?.let { loadBundled(it) }
            ?: DEFAULT

        cache[key] = icon
        return icon
    }

    /** Called after the user changes config, so the next icon fetch re-reads from disk. */
    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    /** Load from local file; on failure return null to let the caller fall back, no exception thrown. */
    private fun loadUserIcon(iconPath: String): Icon? {
        val path = iconPath.trim()
        if (path.isEmpty()) return null

        val file = File(path)
        if (!file.isFile) {
            LOG.warn("AI Agents Extender: icon file does not exist, ignored: $path")
            return null
        }
        return try {
            val raw = IconLoader.findIcon(file.toURI().toURL())
            if (raw == null) {
                LOG.warn("AI Agents Extender: unrecognized icon format, ignored: $path")
                return null
            }
            // User icons may not be 16x16; scale to standard size to avoid breaking the dropdown row height
            if (raw.iconWidth == SIZE && raw.iconHeight == SIZE) raw
            else IconUtil.resizeSquared(raw, SIZE)
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: icon load failed, ignored: $path", e)
            null
        }
    }

    private fun loadBundled(path: String): Icon =
        try {
            val raw = IconLoader.getIcon(path, AgentIcons::class.java.classLoader)
            if (raw.iconWidth == SIZE && raw.iconHeight == SIZE) raw
            else IconUtil.resizeSquared(raw, SIZE)
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: bundled icon load failed: $path", e)
            DEFAULT
        }
}

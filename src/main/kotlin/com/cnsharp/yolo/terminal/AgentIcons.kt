package com.cnsharp.yolo.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import java.io.File
import javax.swing.Icon

/**
 * 自定义工具在下拉列表里显示的图标。
 *
 * 三级回退，任何一级失败都不会让下拉条目消失：
 *   1. 用户在 Settings 里指定的图标文件（iconPath，本地绝对路径）
 *   2. 随插件打包的官方图标（按 agent id 匹配 BUNDLED）
 *   3. 通用图标 DEFAULT
 *
 * 随包图标命名规则与终端内置图标一致：`<name>.svg` + `<name>_dark.svg`，
 * IconLoader 按当前主题自动选用 _dark 变体。尺寸统一 16x16。
 */
object AgentIcons {

    private val LOG = Logger.getInstance(AgentIcons::class.java)

    /** 下拉图标的标准边长，与内置的 claude-code.svg / codex.svg 对齐。 */
    private const val SIZE = 16

    /** 随插件打包的官方图标：agent id -> classpath 资源路径。 */
    private val BUNDLED: Map<String, String> = mapOf(
        "codebuddy" to "/icons/agents/codebuddy.svg",
        "gemini"    to "/icons/agents/gemini.png",
        "copilot"   to "/icons/agents/copilot.svg",
        "cursor"    to "/icons/agents/cursor.png",
        "kimi"      to "/icons/agents/kimi.png",
        "qoder"     to "/icons/agents/qoder.svg",
        "hermes"    to "/icons/agents/hermes.png",
        "opencode"  to "/icons/agents/opencode.png",
        "continue"  to "/icons/agents/continue.png",
        "cline"     to "/icons/agents/cline.png",
        "kilo"      to "/icons/agents/kilo.svg",
        "goose"     to "/icons/agents/goose.png",
        "openclaw"  to "/icons/agents/openclaw.svg"
    )

    /** Skip permissions 勾选框的 y 图标（关/开）。 */
    val SKIP_Y_OFF: Icon by lazy { loadBundled("/icons/agents/skipY.svg") }
    val SKIP_Y_ON: Icon by lazy { loadBundled("/icons/agents/skipYOn.svg") }

    /** 没有专属图标的自定义工具用这个。 */
    val DEFAULT: Icon = AllIcons.Actions.Lightning

    /** 缓存 key 带上 iconPath，用户改了路径后 key 变化会自然重新加载。 */
    private val cache = HashMap<String, Icon>()

    /**
     * 取某个工具的图标。
     *
     * @param agentId  工具 id，用于匹配随包官方图标
     * @param iconPath 用户指定的图标文件绝对路径，留空表示不指定
     */
    @Synchronized
    fun forAgent(agentId: String, iconPath: String = ""): Icon {
        val key = "${agentId.lowercase()}|$iconPath"
        cache[key]?.let { return it }

        val icon = loadUserIcon(iconPath)
            ?: BUNDLED[agentId.lowercase()]?.let { loadBundled(it) }
            ?: DEFAULT

        cache[key] = icon
        return icon
    }

    /** 用户改了配置后调用，让下次取图标重新读盘。 */
    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    /** 从本地文件加载；失败返回 null 交给上层回退，不抛异常。 */
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
            // 用户图标未必是 16x16，统一缩到标准尺寸，避免撑坏下拉行高
            if (raw.iconWidth == SIZE && raw.iconHeight == SIZE) raw
            else IconUtil.resizeSquared(raw, SIZE)
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: icon load failed, ignored: $path", e)
            null
        }
    }

    private fun loadBundled(path: String): Icon =
        try {
            IconLoader.getIcon(path, AgentIcons::class.java.classLoader)
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: bundled icon load failed: $path", e)
            DEFAULT
        }
}

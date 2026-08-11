package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.launcher.SkipPermissionsAction
import com.cnsharp.yolo.settings.AgentDetector
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.DefaultSkipEnvs
import com.cnsharp.yolo.settings.DefaultSkipFlags
import com.cnsharp.yolo.settings.OpenSettingsAction
import com.cnsharp.yolo.settings.PromotedAgents
import com.cnsharp.yolo.terminal.AgentIcons
import com.cnsharp.yolo.util.baseName
import com.cnsharp.yolo.YoloConstants
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.pty4j.PtyProcessBuilder
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * The standalone YOLO panel — a public ToolWindowFactory (registered in plugin.xml) that replicates the
 * Terminal's "AI Agents" dropdown experience without using any internal Terminal API.
 *
 * The panel shows a global "Skip permissions" toggle and a settings gear in its header, and an **agents
 * dropdown** (icon + name) that mirrors the Terminal's AI Agents selector. Selecting an agent immediately
 * opens its interactive terminal — a real PTY — inside the panel, so the agent's TUI runs in place.
 *
 * The terminal is a third-party, fully public component (JediTerm + PTY4J, bundled with the IntelliJ
 * Platform), so no `@ApiStatus.Internal` / `@Experimental` Terminal API is touched and the plugin stays
 * Marketplace-safe.
 */
class YoloToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = YoloPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** One entry in the agents dropdown. */
private data class AgentRow(
    val id: String,
    val displayName: String,
    val command: String,
    val baseArgs: String,
    val skipFlag: String,
    val iconPath: String,
    var installed: Boolean
)

/**
 * Terminal settings for the embedded widget. Extends JediTerm's [DefaultSettingsProvider] and only
 * overrides the font: it reuses the IDE's own console font (the same one IDEA's Terminal uses, which
 * already renders Chinese correctly on this machine), so character cells line up AND CJK glyphs are not
 * clipped. Falls back to a logical monospace if the IDE font can't be resolved.
 */
private class YoloTerminalSettings : DefaultSettingsProvider() {
    private val consoleFont: Font = runCatching {
        val scheme = EditorColorsManager.getInstance().globalScheme
        Font(scheme.consoleFontName, Font.PLAIN, scheme.consoleFontSize)
    }.getOrElse { Font(Font.MONOSPACED, Font.PLAIN, 14) }

    override fun getTerminalFont(): Font = consoleFont
    override fun getTerminalFontSize(): Float = consoleFont.size.toFloat()
}

private class YoloPanel(
    private val project: Project
) : JBPanel<YoloPanel>(), Disposable {

    private val LOG = Logger.getInstance(YoloPanel::class.java)

    private val agentCombo = ComboBox<AgentRow>().apply {
        renderer = object : SimpleListCellRenderer<AgentRow>() {
            override fun customize(list: javax.swing.JList<out AgentRow>, value: AgentRow?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) {
                    // The "AI Agents" prompt item carries no command, so it shows no agent icon.
                    icon = if (value.command.isBlank()) null else AgentIcons.forAgent(value.id, value.iconPath)
                    text = value.displayName
                }
            }
        }
    }

    /** Holds the live terminal widget; swapped on each agent launch. */
    private val terminalHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var currentWidget: JediTermWidget? = null
    private var currentProcess: Process? = null

    /**
     * Guards against stale asynchronous refresh results. Each [rebuild] bumps the generation; only the
     * callback carrying the latest generation is applied. This prevents a slow PATH probe from an earlier
     * rebuild from overwriting a newer one (which could otherwise re-add items and duplicate the list).
     */
    private val refreshGeneration = AtomicInteger(0)

    /**
     * Fired when the OS display scale changes (DPI / monitor switch). A scale change does not always
     * change the component's pixel size, so JediTerm never recomputes its grid and its cached image goes
     * stale — leaving ghost artifacts. We force a recompute ourselves.
     */
    private val scaleChangeListener = PropertyChangeListener { forceTerminalResize(currentWidget) }

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        border = JBUI.Borders.empty(8)

        val group = DefaultActionGroup().apply {
            add(SkipPermissionsAction())
            add(OpenSettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("YOLO.Toolbar", group, true)
        toolbar.targetComponent = this

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(agentCombo, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        // Placeholder shown until the user picks an agent.
        terminalHolder.add(
            JBLabel("Select an agent above to start its terminal").apply {
                horizontalAlignment = javax.swing.SwingConstants.CENTER
            },
            BorderLayout.CENTER
        )

        add(header, BorderLayout.NORTH)
        add(terminalHolder, BorderLayout.CENTER)

        // Selecting an agent launches it immediately (no separate button).
        agentCombo.addActionListener { onAgentSelected() }

        // Track OS display-scale changes so the embedded terminal can recompute (see scaleChangeListener).
        Toolkit.getDefaultToolkit().addPropertyChangeListener("awt.font.desktophints", scaleChangeListener)

        rebuild()
    }

    /** Rebuild the agents dropdown from current settings. */
    private fun rebuild() {
        val rows = buildRows()
        // Show the prompt immediately; installed agents are filled in once the probe below completes.
        agentCombo.removeAllItems()
        agentCombo.addItem(rows.first { it.command.isBlank() })
        agentCombo.selectedIndex = 0
        refreshInstalled(rows)
    }

    /** Probe each command on a background thread, then show only installed agents in the dropdown. */
    private fun refreshInstalled(rows: List<AgentRow>) {
        val gen = refreshGeneration.incrementAndGet()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            rows.forEach { if (it.command.isNotBlank()) it.installed = AgentDetector.canExecute(it.command) }
            app.invokeLater {
                // Ignore this callback if a newer rebuild has started in the meantime.
                if (gen == refreshGeneration.get()) populateInstalled(rows)
            }
        }
    }

    /** Repopulate the dropdown with the prompt plus only the agents that resolved on PATH. */
    private fun populateInstalled(rows: List<AgentRow>) {
        val visible = rows.filter { it.command.isBlank() || it.installed }
        agentCombo.removeAllItems()
        visible.forEach { agentCombo.addItem(it) }
        if (agentCombo.itemCount > 0) agentCombo.selectedIndex = 0
    }

    /** Build the full row set, deduplicated by command so a custom tool sharing a promoted agent's command is not listed twice. */
    private fun buildRows(): List<AgentRow> {
        val settings = AgentExtenderSettings.getInstance().state
        val ruleByCmd = settings.permissionRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })

        fun flagFor(cmd: String, id: String): String {
            val saved = ruleByCmd[baseName(cmd).lowercase()]
            if (!saved.isNullOrBlank()) return saved
            return DefaultSkipFlags.forId(baseName(cmd)).ifBlank { DefaultSkipFlags.forId(id) }
        }

        // Collect into a list, but skip any row whose (non-blank) command was already seen — promoted agents
        // are added before custom tools, so a custom tool duplicating a promoted command is silently dropped.
        val rows = mutableListOf<AgentRow>()
        val seenCommands = mutableSetOf<String>()
        fun addUnique(row: AgentRow) {
            val key = row.command.lowercase().trim()
            if (key.isBlank() || seenCommands.add(key)) rows += row
        }

        // The "AI Agents" prompt is the default selection (shown as the dropdown title). It is not a
        // real agent, so it launches nothing — this also prevents the first real agent from auto-launching
        // when the panel opens and selectedIndex is set programmatically.
        addUnique(AgentRow("", message("panel.agentsPrompt"), "", "", "", "", true))
        for (meta in PromotedAgents.entries) {
            addUnique(AgentRow(meta.id, meta.displayName, meta.command, "", flagFor(meta.command, meta.id), "", false))
        }
        for (tool in settings.customTools) {
            addUnique(
                AgentRow(
                    id = tool.id,
                    displayName = tool.displayName.ifBlank { tool.id },
                    command = tool.command,
                    baseArgs = tool.baseArgs,
                    skipFlag = flagFor(tool.command, tool.id),
                    iconPath = tool.iconPath,
                    installed = false
                )
            )
        }
        return rows
    }

    private fun onAgentSelected() {
        val row = agentCombo.selectedItem as? AgentRow ?: return
        // The default "AI Agents" prompt item has a blank command — never launch it.
        if (row.command.isBlank()) return
        launch(row)
    }

    /** Launch the selected agent into an interactive terminal embedded in the panel. */
    private fun launch(row: AgentRow) {
        val settings = AgentExtenderSettings.getInstance().state

        val cmd = mutableListOf(row.command)
        row.baseArgs.split(' ').filter { it.isNotBlank() }.let { cmd += it }

        // Inherit the OS environment and inject any env-based bypass (e.g. goose's GOOSE_MODE).
        val env = HashMap(System.getenv())
        if (settings.skipEnabled && row.skipFlag.isNotBlank()) {
            // The flag may be multiple tokens (e.g. cline's "--auto-approve true"), so it must be split into
            // separate argv entries; otherwise it becomes one space-containing argument that cannot be parsed.
            val tokens = row.skipFlag.split(' ').filter { it.isNotBlank() }
            if (tokens.isNotEmpty() && tokens[0] !in cmd) cmd += tokens
            DefaultSkipEnvs.forId(baseName(row.command))?.let { (name, value) -> env[name] = value }
        }

        val shellCmd = cmd.joinToString(" ")
        val dir = project.basePath ?: System.getProperty("user.home")
        // Run through an interactive login shell so the agent sees the user's rc-defined PATH (nvm/fnm/…).
        val ptyCommand = if (SystemInfo.isWindows) arrayOf("cmd", "/c", shellCmd)
        else arrayOf("zsh", "-lic", shellCmd)

        LOG.info("AI Agents Extender: starting agent=${row.displayName}, command=$shellCmd")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = PtyProcessBuilder(ptyCommand)
                    .setDirectory(dir)
                    .setEnvironment(env)
                    .setRedirectErrorStream(true)
                    .start()
                val connector = object : ProcessTtyConnector(process, StandardCharsets.UTF_8) {
                    override fun getName(): String = YoloConstants.ID
                }
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val widget = JediTermWidget(YoloTerminalSettings())
                        // File references (path[:line[:col]], ranges, ~/, file://, quoted paths with spaces).
                        widget.addHyperlinkFilter(FileLinkFilter(project, dir))
                        // Stack-trace frames / tracebacks where only the file name is printed (Bar.java:123, File "x", line N).
                        widget.addHyperlinkFilter(StackTraceLinkFilter(project, dir))
                        // Type references (qualified names and project simple names) → class declaration.
                        widget.addHyperlinkFilter(TypeLinkFilter(project))
                        // Class.member / Class#member → the specific method/field/inner class.
                        widget.addHyperlinkFilter(MemberLinkFilter(project))
                        // http(s):// URLs → system browser (does not hide the pane).
                        widget.addHyperlinkFilter(UrlLinkFilter())
                        widget.setTtyConnector(connector)
                        // Add to a laid-out container and force a grid recompute first, then start — so the
                        // terminal's character grid is sized to the real component and a scale change later
                        // triggers a clean recompute (no ghost artifacts).
                        swapTerminal(widget, process)
                        widget.start()
                    } catch (e: Exception) {
                        LOG.warn("AI Agents Extender: failed to embed terminal for ${row.displayName}", e)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("AI Agents Extender: failed to start agent ${row.displayName}", e)
            }
        }
    }

    /** Replace the live terminal with a freshly started one. */
    private fun swapTerminal(widget: JediTermWidget, process: Process) {
        currentWidget?.close()
        currentProcess?.let { runCatching { it.destroyForcibly() } }
        terminalHolder.removeAll()
        terminalHolder.add(widget, BorderLayout.CENTER)

        // JediTerm caches a backing image sized to the component; on a resize or scale change the image
        // can go stale and leave ghost glyphs. Recompute the grid (which recreates the image) on every
        // resize, and once after the first layout settles.
        widget.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                forceTerminalResize(widget)
            }
        })
        terminalHolder.revalidate()
        terminalHolder.repaint()
        SwingUtilities.invokeLater { forceTerminalResize(widget) }

        currentWidget = widget
        currentProcess = process
    }

    /**
     * Force JediTerm to recompute its character grid from the panel's current size and font metrics, then
     * repaint. This recreates the stale backing image (clearing ghost artifacts) after a resize or a scale
     * change that JediTerm would otherwise ignore.
     */
    private fun forceTerminalResize(widget: JediTermWidget?) {
        if (widget == null) return
        val panel = widget.getTerminalPanel()
        val size = panel.size
        if (size.width <= 0 || size.height <= 0) return
        val metrics = panel.getFontMetrics(panel.font)
        val charWidth = metrics.charWidth('M')
        val charHeight = metrics.height
        if (charWidth <= 0 || charHeight <= 0) return
        val cols = max(1, size.width / charWidth)
        val rows = max(1, size.height / charHeight)
        try {
            panel.onResize(TermSize(cols, rows), RequestOrigin.User)
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: failed to resize embedded terminal", e)
        }
        panel.repaint()
    }

    override fun dispose() {
        Toolkit.getDefaultToolkit().removePropertyChangeListener("awt.font.desktophints", scaleChangeListener)
        currentWidget?.close()
        currentProcess?.let { runCatching { it.destroyForcibly() } }
    }
}

package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.launcher.SkipPermissionsAction
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.AgentExtenderSettingsListener
import com.cnsharp.yolo.settings.InstalledAgents
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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.pty4j.PtyProcessBuilder
import com.cnsharp.yolo.terminal.YoloColorPalette
import com.cnsharp.yolo.terminal.YoloJediTermWidget
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    private companion object {
        /** Default width (px) the panel seeds to on first open; capped to the IDE frame width. */
        const val PANEL_DEFAULT_WIDTH = 480
        /** Floor for the seeded initial width: never seed narrower than this even on very narrow windows. */
        const val PANEL_MIN_WIDTH = 360
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Seed the panel's initial width AFTER the tool window is fully registered. Seeding from
        // `init()` crashes on 2026.2: ToolWindowManagerImpl.setToolWindowAnchor dereferences a null
        // internal descriptor during registration and throws "Cannot init toolwindow", which aborts
        // the whole tool-window registration. Doing it here (post-registration) keeps the panel coming
        // up and still applies the width to the first layout only.
        seedInitialWidth(toolWindow)
        val panel = YoloPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Seed the panel's *initial* width to ~38.2% of the IDE window (golden-ratio complement, leaving
     * ~61.8% for the editor). Applied via [ToolWindow.setDefaultState] so it only affects the first
     * layout; afterwards the user's own resize (persisted in workspace.xml) takes precedence.
     *
     * Best-effort only: a missed width is purely cosmetic, so guard the call. Never do this from
     * [ToolWindowFactory.init] — that runs mid-registration and ToolWindowManagerImpl.setToolWindowAnchor
     * dereferences a null internal descriptor and NPEs ("Cannot init toolwindow").
     */
    private fun seedInitialWidth(toolWindow: ToolWindow) {
        val ideFrame = WindowManager.getInstance().getIdeFrame(toolWindow.project) ?: return
        val ideWidth = ideFrame.component.width.takeIf { it > 0 } ?: return
        val width = max(PANEL_MIN_WIDTH, min(ideWidth, PANEL_DEFAULT_WIDTH))
        runCatching {
            toolWindow.setDefaultState(toolWindow.anchor, ToolWindowType.DOCKED, Rectangle(0, 0, width, ideFrame.component.height))
        }
    }
}

/** One entry in the agents dropdown. */
private data class AgentRow(
    val id: String,
    val displayName: String,
    val command: String,
    val baseArgs: String,
    val skipFlag: String,
    val iconPath: String
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

    /**
     * Use a 256-color-aware palette. JediTerm's default palette only resolves the first 16 ANSI
     * indices and asserts `index < 16` for any 256-color (SGR `38;5;n`) text; with assertions enabled
     * in the host JVM that throws from inside `paintComponent` and blanks the whole terminal. Our
     * palette resolves indices 16..255 via the standard xterm-256 formula instead.
     */
    override fun getTerminalColorPalette(): ColorPalette = YoloColorPalette()

    /**
     * The default hyperlink color is pure `java.awt.Color.BLUE` (0,0,255) — far too bright for the panel.
     * Use the user-configured color (Settings | Tools | YOLO), falling back to the muted steel-blue default.
     */
    override fun getHyperlinkColor(): TextStyle {
        val rgb = runCatching { AgentExtenderSettings.getInstance().state.linkColorRgb }
            .getOrDefault(AgentExtenderSettings.DEFAULT_LINK_COLOR_RGB)
        return TextStyle(TerminalColor(rgb), null)
    }
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

    private var currentWidget: YoloJediTermWidget? = null
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
    private val scaleChangeListener = PropertyChangeListener { (currentWidget as? YoloJediTermWidget)?.forceReinitFull() }

    /**
     * Intercepts Ctrl+C while the embedded terminal has focus so the keystroke reaches the PTY as SIGINT
     * (the terminal convention) instead of being swallowed by IDEA's global Copy shortcut.
     *
     * This MUST run ahead of IDEA's own keystroke processing. A plain [java.awt.Toolkit] AWT listener does
     * NOT work: IDEA's [com.intellij.ide.IdeEventQueue] processes shortcuts at the head of the event queue,
     * before Toolkit listeners fire, so the "Shortcuts conflicts" dialog would already be on screen by the
     * time a Toolkit listener could consume the event. Registering an [com.intellij.ide.IdeEventQueue.EventDispatcher]
     * instead lets us intercept the event first and return `true` to stop IDEA from ever treating it as a
     * shortcut — no conflict dialog, no Copy action. We then deliver the key straight to JediTerm, which
     * applies its own selection-aware rule (copy when there is a selection, otherwise SIGINT).
     *
     * Only the real terminal panel is affected — the dropdown, toolbar and the rest of the IDE keep their
     * normal Ctrl+C behavior. ⌘C (macOS Copy) is left untouched because we require a plain Ctrl modifier.
     */
    private val ctrlCDispatcher = IdeEventQueue.EventDispatcher { event ->
        if (event !is KeyEvent || event.id != KeyEvent.KEY_PRESSED) return@EventDispatcher false
        // Plain Ctrl+C only — no Shift/Alt/Meta. Meta (⌘) is left to IDEA's Copy on macOS.
        val mods = event.modifiersEx and
            (InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)
        if (mods != InputEvent.CTRL_DOWN_MASK || event.keyCode != KeyEvent.VK_C) return@EventDispatcher false

        val panel = currentWidget?.getTerminalPanel() ?: return@EventDispatcher false
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@EventDispatcher false
        if (!SwingUtilities.isDescendingFrom(focus, panel)) return@EventDispatcher false

        // Consume before IDEA's action system sees it, then deliver the key to JediTerm ourselves.
        event.consume()
        val forward = KeyEvent(
            panel, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
            InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c'
        )
        panel.processKeyEvent(forward)
        true
    }

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

        // Keep Ctrl+C for the embedded terminal (SIGINT) instead of IDEA's Copy — see ctrlCDispatcher.
        // A Dispatcher intercepts the keystroke at the head of IDEA's event queue, ahead of its shortcut
        // processing, which a Toolkit AWT listener cannot do (the conflict dialog would already be shown).
        IdeEventQueue.getInstance().addDispatcher(ctrlCDispatcher, this)

        // Refresh the dropdown when the user applies changes in Settings | Tools | YOLO (e.g. base-args edits),
        // so an already-open panel picks up the new values instead of keeping its initial rows.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgentExtenderSettings.CHANGED, object : AgentExtenderSettingsListener {
                override fun changed() = rebuild()
            })

        rebuild()
    }

    /** Rebuild the agents dropdown from current settings. */
    private fun rebuild() {
        val rows = buildRows()
        // Fast path: show the persisted installed set immediately (no PATH probes) so the dropdown is instant.
        populateFromCache(rows)
        // Slow path: re-scan installed agents in the background; only rebuild the dropdown if the set changed.
        rescanInstalled(rows)
    }

    /** Populate the dropdown from the persisted installed-agents cache (instant, no PATH probes). */
    private fun populateFromCache(rows: List<AgentRow>) {
        val installed = InstalledAgents.installed()
        val visible = rows.filter { it.command.isBlank() || installed.contains(it.command.lowercase()) }
        agentCombo.removeAllItems()
        visible.forEach { agentCombo.addItem(it) }
        if (agentCombo.itemCount > 0) agentCombo.selectedIndex = 0
    }

    /** Re-scan installed agents on a background thread; update the cache and the dropdown only if it differs. */
    private fun rescanInstalled(rows: List<AgentRow>) {
        val gen = refreshGeneration.incrementAndGet()
        InstalledAgents.rescan(rows.map { it.command }) {
            // Ignore this callback if a newer rebuild has started in the meantime.
            if (gen == refreshGeneration.get()) populateFromCache(rows)
        }
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
        addUnique(AgentRow("", message("panel.agentsPrompt"), "", "", "", ""))
        for (meta in PromotedAgents.entries) {
            val baseArgs = settings.agentBaseArgs[meta.id.lowercase()] ?: ""
            addUnique(AgentRow(meta.id, meta.displayName, meta.command, baseArgs, flagFor(meta.command, meta.id), ""))
        }
        for (tool in settings.customTools) {
            addUnique(
                AgentRow(
                    id = tool.id,
                    displayName = tool.displayName.ifBlank { tool.id },
                    command = tool.command,
                    baseArgs = tool.baseArgs,
                    skipFlag = flagFor(tool.command, tool.id),
                    iconPath = tool.iconPath
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
                        val widget = YoloJediTermWidget(YoloTerminalSettings())
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
                        // Warm the project-type cache off the terminal thread so the first streamed line
                        // doesn't stall while the snapshot is built.
                        if (!DumbService.isDumb(project)) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runCatching { YoloProjectTypes.snapshot(project) }
                            }
                        }
                        // Add to a laid-out container and force a grid recompute first, then start — so the
                        // terminal's character grid is sized to the real component and a scale change later
                        // triggers a clean recompute (no ghost artifacts).
                        swapTerminal(widget, process)
                        widget.start()
                        // Hand keyboard focus to the terminal so the caret lands in the agent's
                        // panel (user can type immediately) and Ctrl+C is delivered to the terminal
                        // as SIGINT instead of being intercepted by IDEA's global Copy shortcut.
                        SwingUtilities.invokeLater { widget.getTerminalPanel().requestFocusInWindow() }
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
    private fun swapTerminal(widget: YoloJediTermWidget, process: Process) {
        currentWidget?.close()
        currentProcess?.let { runCatching { it.destroyForcibly() } }
        terminalHolder.removeAll()
        terminalHolder.add(widget, BorderLayout.CENTER)

        // JediTerm caches a backing image sized to the component; on a resize the grid can go stale and
        // leave ghost glyphs. Recompute JediTerm's own grid (its internal character-size math) on every
        // resize — this clears ghosts without re-deriving the font (which would blur it on HiDPI). A full
        // font+grid recompute is reserved for OS display-scale changes (see scaleChangeListener).
        widget.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                forceReinit(widget)
            }
        })
        terminalHolder.revalidate()
        terminalHolder.repaint()
        SwingUtilities.invokeLater { forceReinit(widget) }

        currentWidget = widget
        currentProcess = process
    }

    /**
     * Resize-only recompute of the embedded terminal's grid from the panel's current size. Clears
     * ghost/duplicate glyphs that appear when JediTerm's cached image goes out of sync with the actual
     * component size, without re-deriving the font (which would blur it on HiDPI). Full font+grid
     * recompute on display-scale changes goes through [YoloJediTermWidget.forceReinitFull] instead.
     */
    private fun forceReinit(widget: YoloJediTermWidget?) {
        if (widget == null) return
        try {
            widget.forceReinit()
        } catch (e: Exception) {
            LOG.warn("AI Agents Extender: failed to reinit embedded terminal", e)
        }
    }

    override fun dispose() {
        IdeEventQueue.getInstance().removeDispatcher(ctrlCDispatcher)
        Toolkit.getDefaultToolkit().removePropertyChangeListener("awt.font.desktophints", scaleChangeListener)
        currentWidget?.close()
        currentProcess?.let { runCatching { it.destroyForcibly() } }
    }
}

package com.cnsharp.yolo.terminal

import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * A [JediTermWidget] subclass whose terminal panel forces JediTerm to recompute its grid once, at the
 * right moment, so the embedded terminal comes up crisp and correctly sized.
 *
 * JediTerm's own [TerminalPanel] already handles ongoing resizes correctly and blur-free: its built-in
 * `componentResized` listener calls `sizeTerminalFromComponent()`, which recomputes the character grid
 * WITHOUT re-deriving the font (so HiDPI text stays sharp) and recreates the backing image via
 * `postResize` (so no ghost/duplicate glyphs). We must NOT add our own resize listener on top of that —
 * doing so double-fires and makes the panel laggy.
 *
 * The one thing JediTerm does not give us for free is a clean *initial* grid. When the panel is first
 * constructed its size is still zero, so the first size sync (triggered by JediTerm's hierarchy listener)
 * can land before the component is laid out, leaving the TUI garbled. So we attach a one-shot
 * `componentResized` listener that runs [reinitFontAndResize] exactly once, the first time the panel
 * actually has a real (non-zero) size. That single call establishes the font metrics and the grid at the
 * correct dimensions; every resize after that is handled by JediTerm itself.
 *
 * For OS display-scale / DPI changes (where the component's pixel size may not change and JediTerm never
 * recomputes), [YoloTerminalPanel.forceReinitFull] re-derives the font via [YoloJediTermWidget.forceReinitFull].
 */
class YoloJediTermWidget(settings: SettingsProvider) : JediTermWidget(settings) {

    override fun createTerminalPanel(
        settings: SettingsProvider,
        style: StyleState,
        buffer: TerminalTextBuffer
    ): TerminalPanel = YoloTerminalPanel(settings, buffer, style)

    /** Full font + grid recompute, for OS display-scale / DPI changes. */
    fun forceReinitFull() {
        (myTerminalPanel as? YoloTerminalPanel)?.forceReinitFull()
    }
}

/**
 * Exposes JediTerm's protected [reinitFontAndResize] plus a one-shot initial recompute.
 */
class YoloTerminalPanel(
    settings: SettingsProvider,
    buffer: TerminalTextBuffer,
    style: StyleState
) : TerminalPanel(settings, buffer, style) {

    private var initialReinitDone = false

    init {
        // One-shot: the first time this panel reaches a real (non-zero) size, recompute the font metrics
        // and the character grid. This must happen after layout has settled — a premature call (e.g. from
        // an invokeLater that runs before the component is sized) computes a wrong grid and garbles the TUI.
        // After this single call, JediTerm's own resize handling takes over for every subsequent resize.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (initialReinitDone) return
                if (width <= 0 || height <= 0) return
                initialReinitDone = true
                runCatching { reinitFontAndResize() }
            }
        })
    }

    /** Full font + grid recompute (also used for OS display-scale / DPI changes). */
    fun forceReinitFull() {
        runCatching { reinitFontAndResize() }
    }
}

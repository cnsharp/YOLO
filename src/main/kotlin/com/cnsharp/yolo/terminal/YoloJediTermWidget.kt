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
 * A [JediTermWidget] subclass whose terminal panel keeps JediTerm crisp and correctly sized across
 * HiDPI / scale changes.
 *
 * JediTerm's own [TerminalPanel] already handles ongoing resizes correctly and blur-free: its built-in
 * `componentResized` listener calls `sizeTerminalFromComponent()`, which recomputes the character grid
 * WITHOUT re-deriving the font (so HiDPI text stays sharp) and recreates the backing image via
 * `postResize` (so no ghost/duplicate glyphs). We must NOT re-derive the font on every resize event — that
 * double-fires and makes the panel laggy.
 *
 * The gap JediTerm leaves is the *initial* font scale. The panel's `GraphicsConfiguration` (and thus the
 * real device scale) is often not attached until after the tool window is first shown, so a single init at
 * the first non-zero size can bake a 1.0-scale font on a 2.0 display and stay blurry for the whole session.
 * [YoloTerminalPanel] therefore re-derives the font only when the effective device scale *changes* (initial
 * config attach, monitor switch, OS scale change) — see its scale tracker. That corrects the baked-in
 * wrong-scale font without re-initing on every splitter drag.
 *
 * For OS display-scale / DPI changes, [YoloJediTermWidget.forceReinitFull] re-derives the font via
 * [YoloTerminalPanel.forceReinitFull].
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
 * Exposes JediTerm's protected [reinitFontAndResize] and keeps the terminal crisp across HiDPI / scale
 * changes.
 *
 * JediTerm's own resize handler recomputes the grid and recreates the backing image *without* re-deriving
 * the font, so text stays sharp — but only if the font was originally derived at the correct device scale.
 * The panel's [java.awt.GraphicsConfiguration] (and thus the real scale) is often not attached until after
 * the tool window is first shown, so a one-shot init at the first non-zero size can bake a 1.0-scale font on
 * a 2.0 display and stay blurry for the whole session. We therefore re-derive the font whenever the
 * effective device scale actually changes: the initial config attach, a monitor switch, or an OS scale
 * change. Because we only re-init on a *scale* change (not on every resize event), dragging the splitter
 * stays lag-free and JediTerm's own font-free resize keeps the text sharp.
 */
class YoloTerminalPanel(
    settings: SettingsProvider,
    buffer: TerminalTextBuffer,
    style: StyleState
) : TerminalPanel(settings, buffer, style) {

    /** Device scale (e.g. 2.0 on a Retina display) at which the font was last derived. -1 = not yet done. */
    private var lastScale = -1.0

    init {
        // Re-derive the font when the panel first reaches a real size (initial grid) and whenever the
        // component is resized to a different device scale.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (width <= 0 || height <= 0) return
                maybeReinitForScale()
            }
        })
        // The graphics configuration (real HiDPI scale) may only become available after the tool window is
        // shown; re-derive when it arrives so an early 1.0-scale font is corrected to 2.0.
        addPropertyChangeListener("graphicsConfiguration") { maybeReinitForScale() }
    }

    /** Re-derive the font + grid only if the effective device scale changed; otherwise let JediTerm's own
     *  (font-free, lag-free) resize handling do its job. */
    private fun maybeReinitForScale() {
        val scale = deviceScale()
        if (scale != lastScale) reinitAtCurrentScale()
    }

    private fun reinitAtCurrentScale() {
        lastScale = deviceScale()
        runCatching { reinitFontAndResize() }
    }

    private fun deviceScale(): Double =
        graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0.0 } ?: 1.0

    /** Full font + grid recompute (used for OS display-scale / DPI changes via [scaleChangeListener]). */
    fun forceReinitFull() {
        runCatching { reinitFontAndResize() }
        lastScale = deviceScale()
    }
}

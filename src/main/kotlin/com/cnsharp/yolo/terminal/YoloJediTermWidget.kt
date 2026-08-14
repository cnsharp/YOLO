package com.cnsharp.yolo.terminal

import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider

/**
 * A [JediTermWidget] subclass whose terminal panel exposes [reinitFontAndResize] so the YOLO panel can
 * force JediTerm to recompute its grid after a layout settle or a scale change.
 *
 * Two flavors are exposed:
 *  - [YoloTerminalPanel.forceReinit] — resize-only: recompute the character grid with JediTerm's *own*
 *    cell math (`getTerminalSizeFromComponent` + `onResize`). This clears the ghost/duplicate glyphs that
 *    appear when the cached image goes out of sync with the component size, but does NOT re-derive the
 *    font. Re-running `initFont()` on every resize re-creates the `java.awt.Font` and blurs it on HiDPI
 *    displays, so we avoid that on the frequent horizontal-resize path.
 *  - [YoloTerminalPanel.forceReinitFull] — font + grid (`reinitFontAndResize`). Only needed when the OS
 *    display scale / DPI changes, where the font itself must be re-derived to stay crisp.
 */
class YoloJediTermWidget(settings: SettingsProvider) : JediTermWidget(settings) {

    override fun createTerminalPanel(
        settings: SettingsProvider,
        style: StyleState,
        buffer: TerminalTextBuffer
    ): TerminalPanel = YoloTerminalPanel(settings, buffer, style)

    /** Resize-only recompute (clears ghosts without re-deriving the font — avoids HiDPI blur on resize). */
    fun forceReinit() {
        (myTerminalPanel as? YoloTerminalPanel)?.forceReinit()
    }

    /** Full font + grid recompute, for OS display-scale / DPI changes. */
    fun forceReinitFull() {
        (myTerminalPanel as? YoloTerminalPanel)?.forceReinitFull()
    }
}

/**
 * Exposes JediTerm's protected [reinitFontAndResize] plus a resize-only variant. The base method is
 * `protected` and recreates the backing image using JediTerm's internal character-size calculation
 * (char width from 'W', height from font metrics + line spacing).
 */
class YoloTerminalPanel(
    settings: SettingsProvider,
    buffer: TerminalTextBuffer,
    style: StyleState
) : TerminalPanel(settings, buffer, style) {

    /** Resize-only: recompute the grid from the component size using JediTerm's own cell math, then resize.
     *  Skips [reinitFontAndResize]'s `initFont()` step so the font is not re-derived (and thus not blurred)
     *  on every horizontal resize. */
    fun forceReinit() {
        val size = getTerminalSizeFromComponent() ?: return
        onResize(size, RequestOrigin.User)
    }

    /** Full font + grid recompute. */
    fun forceReinitFull() {
        reinitFontAndResize()
    }
}

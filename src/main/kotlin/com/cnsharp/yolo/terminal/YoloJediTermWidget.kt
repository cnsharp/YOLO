package com.cnsharp.yolo.terminal

import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider

/**
 * A [JediTermWidget] subclass whose terminal panel exposes [reinitFontAndResize] so the YOLO panel can
 * force JediTerm to recalculate its own font metrics and recreate the backing image after the widget is
 * laid out or the display scale changes. Using JediTerm's own resize path (instead of computing cols/rows
 * externally) prevents ghost/duplicate glyphs that appear when the terminal's cached image gets out of sync
 * with the actual component size.
 */
class YoloJediTermWidget(settings: SettingsProvider) : JediTermWidget(settings) {

    override fun createTerminalPanel(
        settings: SettingsProvider,
        style: StyleState,
        buffer: TerminalTextBuffer
    ): TerminalPanel = YoloTerminalPanel(settings, buffer, style)

    /** Recalculate font metrics and resize the terminal grid/image. Safe to call multiple times. */
    fun forceReinit() {
        (myTerminalPanel as? YoloTerminalPanel)?.forceReinit()
    }
}

/**
 * Exposes [reinitFontAndResize] publicly; the base method is `protected` and recreates the backing image
 * using JediTerm's internal character-size calculation (char width from 'W', height from font metrics +
 * line spacing).
 */
class YoloTerminalPanel(
    settings: SettingsProvider,
    buffer: TerminalTextBuffer,
    style: StyleState
) : TerminalPanel(settings, buffer, style) {
    fun forceReinit() = reinitFontAndResize()
}

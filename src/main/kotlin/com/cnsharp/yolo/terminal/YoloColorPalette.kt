package com.cnsharp.yolo.terminal

import com.jediterm.core.Color as JediColor
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.emulator.ColorPalette

/**
 * 256-color-capable palette for the embedded terminal.
 *
 * JediTerm's stock [ColorPalette] only resolves the first 16 ANSI indices: its public
 * `getForeground(TerminalColor)` / `getBackground(TerminalColor)` assert the index is `< 16`, and its
 * default 16-entry table would otherwise throw for any 256-color (SGR `38;5;n`) or RGB-packed text.
 * With assertions enabled in the host JVM the assert becomes a hard [AssertionError] thrown from inside
 * `TerminalPanel.paintComponent`, which leaves the whole terminal blank (no grid is ever drawn).
 *
 * We override the palette so colored agent output (CodeBuddy, Claude, …) renders instead of crashing:
 *  - non-indexed colors (truecolor `38;2;r;g;b`) are passed through via [TerminalColor.toColor];
 *  - indexed colors `0..255` resolve via the standard xterm-256 formula;
 *  - indexed colors `> 255` are RGB-packed values (some code paths store `0xRRGGBB` in the index
 *    field) and are unpacked directly.
 */
class YoloColorPalette : ColorPalette() {

    override fun getForeground(color: TerminalColor): JediColor =
        if (color.isIndexed) indexed(color.colorIndex) else color.toColor()

    override fun getBackground(color: TerminalColor): JediColor =
        if (color.isIndexed) indexed(color.colorIndex) else color.toColor()

    override fun getForegroundByColorIndex(index: Int): JediColor = indexed(index)

    override fun getBackgroundByColorIndex(index: Int): JediColor = indexed(index)

    private fun indexed(index: Int): JediColor =
        if (index in 0..255) JediColor(xterm256Rgb(index))
        else JediColor((index shr 16) and 0xFF, (index shr 8) and 0xFF, index and 0xFF)

    companion object {
        /** Standard xterm 16 system colors (RGB ints). */
        private val ANSI16 = intArrayOf(
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
            0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
            0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
        )

        /** Resolve a palette index (0..255) to an xterm-256 RGB int. */
        fun xterm256Rgb(index: Int): Int {
            require(index in 0..255) { "color index out of range: $index" }
            return when {
                index < 16 -> ANSI16[index]
                index < 232 -> {
                    val i = index - 16
                    val r = (i / 36) % 6
                    val g = (i / 6) % 6
                    val b = i % 6
                    val rv = if (r == 0) 0 else 55 + r * 40
                    val gv = if (g == 0) 0 else 55 + g * 40
                    val bv = if (b == 0) 0 else 55 + b * 40
                    (rv shl 16) or (gv shl 8) or bv
                }
                else -> {
                    val v = 8 + (index - 232) * 10
                    (v shl 16) or (v shl 8) or v
                }
            }
        }
    }
}

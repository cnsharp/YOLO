package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import java.awt.Color

/**
 * Always-on highlight for every link this plugin contributes to the terminal.
 *
 * [com.intellij.execution.filters.Filter.ResultItem] carries three separate attribute slots —
 * `highlightAttributes`, `hoveredHyperlinkAttributes` and `followedHyperlinkAttributes`. The terminal's own
 * generic file links only supply the hovered one, which is why they appear solely on mouse hover. Supplying
 * `highlightAttributes` here is what makes our links stay underlined **without** hovering.
 *
 * The colour is fixed rather than taken from an [com.intellij.openapi.editor.colors.EditorColorsScheme],
 * because a `ConsoleFilterProvider` has no access to one.
 */
internal val HIGHLIGHT_ATTRS: TextAttributes = TextAttributes().apply {
    effectType = EffectType.LINE_UNDERSCORE
    effectColor = Color(0x58, 0x9A, 0xE5)
}

/**
 * Wraps a resolve-then-navigate pair as a terminal hyperlink.
 *
 * Keeping every detector's links on one line is handled by [ExpLinkFilter], which runs the detectors and
 * merges their spans itself. That filter deliberately does NOT set `NextAction.CONTINUE_FILTERING` on its
 * result: the terminal does not render results carrying that action at all, and the default `EXIT` is
 * harmless once the detectors are no longer handed to `CompositeFilter` separately.
 *
 * [resolve] runs on a pooled thread **inside a read action** and returns the navigation to perform, or
 * null when the reference cannot be resolved (the click then simply does nothing). The returned action is
 * posted to the EDT.
 *
 * This split is mandatory, not stylistic: resolving a reference touches the index
 * (`ChooseByNameContributor.getNames`, `FilenameIndex` → StubIndex), and index access on the EDT trips
 * `SlowOperations` ("Slow operations are prohibited on EDT"). Conversely, the navigation step opens an
 * editor and therefore *must* be on the EDT.
 *
 * This is the counterpart of main's `yoloHyperlink()` with two deliberate differences:
 *  1. main resolves and navigates together inside `invokeLater`; that is safe on its JediTerm widget but
 *     not here, where the same code path reaches the platform index.
 *  2. main additionally hides the `YOLO` tool window after navigating. This plugin drives IDEA's own
 *     Terminal instead — hiding it on every link click would be hostile.
 */
internal fun expLink(resolve: () -> (() -> Unit)?): HyperlinkInfo = object : HyperlinkInfo {
    override fun navigate(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val navigate = runReadActionBlocking { resolve() }
            if (navigate != null) {
                ApplicationManager.getApplication().invokeLater { navigate() }
            }
        }
    }
}

/**
 * True for lines that are part of a unified diff, where a path is a hunk header rather than a file the
 * user can open. Only the explicit diff markers count — a bare `- ` / `+ ` is deliberately excluded
 * because it collides with markdown bullets and shell commands like `rm -rf`.
 */
internal fun isDiffLine(text: String): Boolean {
    val t = text.trimStart()
    if (t.isEmpty()) return false
    return t.startsWith("@@") ||
        t.startsWith("--- ") || t.startsWith("+++ ")
}

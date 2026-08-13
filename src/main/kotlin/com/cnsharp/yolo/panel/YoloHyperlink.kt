package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.jediterm.terminal.model.hyperlinks.LinkInfo

/**
 * Build a terminal [LinkInfo] whose click runs [navigate] on the EDT (e.g. open a file or PSI
 * declaration) and then auto-hides the YOLO tool window so it no longer obscures the editor.
 *
 * Shared by [FileLinkFilter] and [TypeLinkFilter] to avoid duplicating the navigate-and-hide wiring.
 */
internal fun yoloHyperlink(project: Project?, navigate: () -> Unit): LinkInfo =
    LinkInfo(Runnable {
        ApplicationManager.getApplication().invokeLater {
            navigate()
            project?.let { ToolWindowManager.getInstance(it).getToolWindow(YoloConstants.ID)?.hide() }
        }
    })

/**
 * Detects whether [text] is a line of a unified diff's *structural* metadata, so links are suppressed on
 * it. A line qualifies when, after optional leading indentation, it begins with one of:
 *  - `@@` (a hunk header, e.g. `@@ -12,7 +12,9 @@`);
 *  - `--- ` / `+++ ` (diff file headers, e.g. `--- a/src/Foo.kt` / `+++ b/src/Foo.kt`).
 *
 * We deliberately do **not** suppress bare `- ` / `+ ` lines: a markdown unordered-list item (`- item`)
 * or task-list / `+ ` bullet is indistinguishable from a diff deletion/addition by that rule, and
 * suppressing it would drop every link inside such a list. The `- ` / `+ ` markers above are kept so a
 * shell command like `rm -rf` (a dash not followed by a space) is never mistaken for a diff.
 */
internal fun isDiffLine(text: String): Boolean {
    val t = text.trimStart()
    if (t.isEmpty()) return false
    return t.startsWith("@@") ||
        t.startsWith("--- ") || t.startsWith("+++ ")
}

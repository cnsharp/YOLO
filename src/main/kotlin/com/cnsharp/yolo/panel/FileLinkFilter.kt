package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File

/**
 * Makes file references printed by agents clickable in the embedded terminal.
 *
 * Matches references of the form `path`, `path:line`, `path:line:column`, and `path:line-line` (a line
 * range — opens at the start line), e.g. `src/foo/Bar.kt:42`, `/abs/Bar.kt:42:13`, `C:\foo\Bar.kt:7`,
 * `./Makefile:10`, `~/x/y.kt:3`. Paths inside quotes (allowing embedded spaces, e.g.
 * `"/path with space/Bar.kt":5`) are also linked. `file://` URIs are accepted. When the file actually
 * exists in the project (or the agent's working dir / a content root), the reference becomes a hyperlink
 * that opens it in the IDE editor; clicking also hides the YOLO pane.
 *
 * Built entirely on public APIs: JediTerm's [HyperlinkFilter] / [LinkInfo] for the terminal link, and
 * IntelliJ's [com.intellij.openapi.fileEditor.OpenFileDescriptor] / [com.intellij.openapi.fileEditor.FileEditorManager]
 * for navigation — so it stays Marketplace-safe.
 */
class FileLinkFilter(
    private val project: Project?,
    private val baseDir: String
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text)) return null
        val items = mutableListOf<LinkResultItem>()

        // Quoted paths first (may contain spaces, e.g. `"/path with space/Bar.kt":5`). Their full spans are
        // recorded so the unquoted pass below can suppress a *sub-path* that falls inside the quotes — e.g.
        // `space/Bar.kt` inside `"/path with space/Bar.kt"` would otherwise be linked twice.
        val quotedSpans = mutableListOf<Pair<Int, Int>>()
        val q = QUOTED_PATH_PATTERN.matcher(text)
        while (q.find()) {
            val raw = q.group(2)
            // Same truncation guard as below: skip `…`/`...` marked (incomplete) paths.
            if (raw.contains('…') || raw.contains("...") || isTruncatedPath(text, q.end(2))) continue
            val line = q.group(3)?.toIntOrNull()
            val column = q.group(4)?.toIntOrNull()
            val link = yoloHyperlink(project) {
                val file = resolve(raw) ?: return@yoloHyperlink
                val p = project ?: return@yoloHyperlink
                openFileAt(p, file, line, column)
            }
            // Span the path (group 2) only — `q.end()` includes the closing quote, which must not be
            // clickable. A `:line` reference (outside the closing quote) is still included.
            items.add(LinkResultItem(q.start(2), q.end() - 1, link))
            quotedSpans.add(q.start() to q.end())
        }

        // Standard (unquoted) path references.
        val m = PATH_PATTERN.matcher(text)
        while (m.find()) {
            // Skip a match that lies inside a quoted path (see quotedSpans above).
            if (quotedSpans.any { m.start(1) < it.second && m.end() > it.first }) continue
            val raw = m.group(1)
            val hasExt = m.group(2) != null
            val hasLine = m.group(3) != null
            // Skip bare extension-less, line-less paths: they are either directory references (not openable)
            // or — more importantly — fragments of a long path the terminal hard-wrapped across lines, which
            // would otherwise be painted as broken links (see PATH_PATTERN's completion requirement).
            if (!hasExt && !hasLine) continue
            // A `…`/`...` truncation marker means the path is incomplete — skip it so we never link a broken
            // prefix (e.g. `/Users/me/Proj…name` or `/Users/me/Proj...name`). The ASCII-safe path class stops
            // at `…`, so the marker lands *just after* the captured path (m.end(1)); check both inside and
            // at the boundary.
            if (raw.contains('…') || raw.contains("...") || isTruncatedPath(text, m.end(1))) continue
            val line = m.group(3)?.toIntOrNull()
            val column = m.group(5)?.toIntOrNull()
            // group(1) is the path without its extension; re-attach it for resolution (m.group(2) is nullable).
            val fullPath = raw + (m.group(2)?.let { ".$it" } ?: "")
            // Resolution is deferred to click time (see resolve) so streaming output is never blocked by
            // index/PSI queries on the terminal emulator thread.
            val link = yoloHyperlink(project) {
                val file = resolve(fullPath) ?: return@yoloHyperlink
                val p = project ?: return@yoloHyperlink
                openFileAt(p, file, line, column)
            }
            // Span the whole reference (path + optional :line:column) so a click anywhere navigates.
            items.add(LinkResultItem(m.start(1), m.end(), link))
        }

        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a possibly-relative path against the agent's working dir and the project's content roots.
     *  Called from the click handler (EDT), wrapped in a read action because it touches the project model. */
    private fun resolve(raw: String): File? = ReadAction.compute<File?, Throwable> {
        val project = this.project ?: return@compute null
        val candidates = mutableListOf<File>()
        when {
            raw.startsWith("file://") -> candidates += File(raw.removePrefix("file://"))
            raw.startsWith("~/") -> candidates += File(System.getProperty("user.home"), raw.removePrefix("~/"))
            raw == "~" -> candidates += File(System.getProperty("user.home"))
        }
        candidates += File(raw)
        candidates += File(baseDir, raw)
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            candidates += File(root.path, raw)
        }
        candidates.firstOrNull { it.isFile }
    }

    companion object {
        /**
         * True when the path captured by a match is truncated: the char right after the captured path
         * (at [pathEnd]) is a `…` (U+2026) or a `...` run. Because the path component class is ASCII-safe,
         * `…` is never consumed into the captured group, so it always shows up at this boundary and must be
         * checked here — `raw.contains('…')` alone would miss it.
         */
        private fun isTruncatedPath(text: String, pathEnd: Int): Boolean {
            if (pathEnd >= text.length) return false
            val c = text[pathEnd]
            return c == '…' || (c == '.' && text.startsWith("...", pathEnd))
        }
    }
}

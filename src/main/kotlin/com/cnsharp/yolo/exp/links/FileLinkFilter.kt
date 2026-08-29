package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

/**
 * Makes file references printed by agents clickable in the terminal.
 *
 * Matches references of the form `path`, `path:line`, `path:line:column`, and `path:line-line` (a line
 * range — opens at the start line), e.g. `src/foo/Bar.kt:42`, `/abs/Bar.kt:42:13`, `C:\foo\Bar.kt:7`,
 * `./Makefile:10`, `~/x/y.kt:3`. Paths inside quotes (allowing embedded spaces, e.g.
 * `"/path with space/Bar.kt":5`) are also linked. `file://` URIs are accepted. When the file actually
 * exists in the project (or the agent's working dir / a content root), the reference becomes a hyperlink
 * that opens it in the IDE editor.
 *
 * Existence is deliberately NOT checked while matching: the filter only pattern-matches, so streaming
 * output is never blocked by filesystem or index work. Resolution happens on click in [resolve], and a
 * click on a path that does not exist simply does nothing.
 *
 * A reference that is a fragment of a truncated path (e.g. `…` in the middle) is never linked — see the
 * truncation guards in [applyFilter] and [StackTraceLinkFilter].
 *
 * Built entirely on public APIs: the platform's [Filter] / `HyperlinkInfo` for the terminal link, and
 * `OpenFileDescriptor` / `FileEditorManager` for navigation — so it stays Marketplace-safe.
 */
class FileLinkFilter(
    private val project: Project?,
    private val baseDir: String,
    private val wrapState: PathWrapState? = null
) : Filter {

    override fun applyFilter(text: String, entireLength: Int): Filter.Result? {
        if (text.isBlank() || isDiffLine(text)) {
            // A blank or diff line breaks any pending wrap sequence.
            wrapState?.pendingPrefix = ""
            wrapState?.continuationSpan = null
            return null
        }
        val items = mutableListOf<Filter.ResultItem>()

        // --- Hard-wrap path reconstruction ---
        // Filtering is driven per physical line. When a long path wraps at terminal width, the head line
        // ends with a path fragment (no extension, no :line) and we store it in wrapState.pendingPrefix.
        // On the very next call (the continuation line) we prepend that prefix, re-match PATH_PATTERN on
        // the joined text, and create a link for the tail portion inside the current line. The link
        // resolves the FULL reconstructed path so navigation is correct even though only the tail is
        // visible and clickable.
        val prefix = wrapState?.pendingPrefix ?: ""
        wrapState?.pendingPrefix = ""
        wrapState?.continuationSpan = null

        if (prefix.isNotEmpty()) {
            val trimmed = text.trimStart()
            val leading = text.length - trimmed.length
            val combined = prefix + trimmed
            val cm = PATH_PATTERN.matcher(combined)
            while (cm.find()) {
                val matchStart = cm.start(1)
                val matchEnd = cm.end()
                // Only care about matches that span the prefix/continuation boundary.
                if (matchStart >= prefix.length || matchEnd <= prefix.length) continue
                val rawC = cm.group(1)
                val hasExtC = cm.group(2) != null
                val hasLineC = cm.group(3) != null
                if (!hasExtC && !hasLineC) {
                    // Still no ext/line — path wraps again; store new prefix for next line.
                    val restC = combined.substring(matchEnd)
                    if (isBlankOrPathSeparator(restC)) wrapState?.pendingPrefix = rawC + leadingSeparators(restC)
                    continue
                }
                if (rawC.contains('…') || rawC.contains("...") || isTruncatedPath(combined, cm.end(1))) continue
                val lineNum = cm.group(3)?.toIntOrNull()
                val col = cm.group(5)?.toIntOrNull()
                val fullPath = fileLinkTarget(rawC, cm.group(2))
                val link = expLink {
                    val file = resolve(fullPath) ?: return@expLink null
                    val p = project ?: return@expLink null
                    { openFileAt(p, file, lineNum, col) }
                }
                // Map the tail portion back to positions within `text`.
                val tailStart = leading
                val tailEnd = leading + (matchEnd - prefix.length)
                if (tailEnd <= text.length) {
                    items.add(Filter.ResultItem(tailStart, tailEnd, link, HIGHLIGHT_ATTRS))
                    wrapState?.continuationSpan = tailStart until tailEnd
                }
            }
        }
        // --- End hard-wrap reconstruction ---

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
            val link = expLink {
                val file = resolve(raw) ?: return@expLink null
                val p = project ?: return@expLink null
                { openFileAt(p, file, line, column) }
            }
            // Span the path (group 2) only — the quotes must not be clickable. When a `:line[:col]` follows
            // the closing quote (group 3), add it as a *second* link so the whole reference is clickable
            // without ever painting the quotes blue. A single span cannot skip the quote in the middle, so
            // the previous `q.end() - 1` trick wrongly dropped the last column digit on `":line:col"`.
            items.add(Filter.ResultItem(q.start(2), q.end(2), link, HIGHLIGHT_ATTRS))
            if (q.group(3) != null) {
                items.add(Filter.ResultItem(q.start(3) - 1, q.end(), link, HIGHLIGHT_ATTRS))
            }
            quotedSpans.add(q.start() to q.end())
        }

        // Standard (unquoted) path references.
        val m = PATH_PATTERN.matcher(text)
        // A tail the reconstruction above already linked must not be linked again here: when a path wraps
        // mid-directory (`src/ma` | `in/java/.../Foo.java:33`), the tail is by itself a perfectly valid
        // relative path (it has separators and a real extension), so this pass would emit a duplicate.
        val continuationSpan = wrapState?.continuationSpan
        while (m.find()) {
            // Skip a match that lies inside a quoted path (see quotedSpans above).
            if (quotedSpans.any { m.start(1) < it.second && m.end() > it.first }) continue
            // Skip a match the hard-wrap reconstruction already linked (same overlap test as
            // StackTraceLinkFilter uses against this span).
            if (continuationSpan != null
                && m.start(1) < continuationSpan.last
                && m.end() > continuationSpan.first) continue
            // Skip the tail of a truncated path: a `…`/`...` immediately before the path start (e.g.
            // `Read(src/main/kotlin/com/cnshar…/real/Bar.kt)`). The agent only abbreviated the middle, so
            // the fragment after the marker is not a real file.
            if (isTruncatedPathHead(text, m.start(1))) continue
            val raw = m.group(1)
            val hasExt = m.group(2) != null
            val hasLine = m.group(3) != null
            // Skip bare extension-less, line-less paths: they are either directory references (not openable)
            // or — more importantly — fragments of a long path the terminal hard-wrapped across lines, which
            // would otherwise be painted as broken links (see PATH_PATTERN's completion requirement).
            if (!hasExt && !hasLine) {
                // Hard-wrap head detection: if this path fragment reaches the end of the line content,
                // store it so the next physical line can attempt reconstruction.
                // Guard: only store when the last path segment has NO dot. A dot in the last segment
                // means the wrap split inside the extension (e.g. "build.gradl" for ".gradle") and the
                // continuation line would carry only the remaining extension chars — creating a 1–2
                // character phantom link (e.g. just "e"). Without a dot the fragment ends mid-name
                // (e.g. "OrderTransitionContext" before ".java"), which is the correct wrap case.
                val rest = text.substring(m.end())
                if (wrapState != null && isBlankOrPathSeparator(rest)) {
                    val lastSep = raw.lastIndexOfAny(charArrayOf('/', '\\'))
                    val lastSegment = if (lastSep >= 0) raw.substring(lastSep + 1) else raw
                    // Keep the separator run PATH_PATTERN had to leave behind, or a wrap landing right
                    // after a `/` would reconstruct `…/comcnsharp/…` instead of `…/com/cnsharp/…`.
                    if (!lastSegment.contains('.')) wrapState.pendingPrefix = raw + leadingSeparators(rest)
                }
                continue
            }
            // A `…`/`...` truncation marker means the path is incomplete — skip it so we never link a broken
            // prefix (e.g. `/Users/me/Proj…name` or `/Users/me/Proj...name`). The ASCII-safe path class stops
            // at `…`, so the marker lands *just after* the captured path (m.end(1)); check both inside and
            // at the boundary.
            if (raw.contains('…') || raw.contains("...") || isTruncatedPath(text, m.end(1))) continue
            val line = m.group(3)?.toIntOrNull()
            val column = m.group(5)?.toIntOrNull()
            // group(1) already carries the full path *including* its extension; just hand it to [resolve].
            // (Historically this re-appended `.${group(2)}`, yielding a doubled `Foo.kt.kt` that never resolved.)
            val fullPath = fileLinkTarget(raw, m.group(2))
            // Resolution is deferred to click time (see resolve) so streaming output is never blocked by
            // index/PSI queries on the filtering thread.
            val link = expLink {
                val file = resolve(fullPath) ?: return@expLink null
                val p = project ?: return@expLink null
                { openFileAt(p, file, line, column) }
            }
        // Span the whole reference (path + optional :line:column) so a click anywhere navigates.
        items.add(Filter.ResultItem(m.start(1), m.end(), link, HIGHLIGHT_ATTRS))
        }

        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /** Resolve a possibly-relative path against the agent's working dir and the project's content roots.
     *  Called from the click handler (EDT), wrapped in a read action because it touches the project model. */
    private fun resolve(raw: String): File? {
        val project = this.project ?: return null
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
        return candidates.firstOrNull { it.isFile }
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

/**
 * The run of path separators at the start of [rest] — the part [PATH_PATTERN] cannot carry in its own
 * match, because its final component must be non-separator characters.
 */
private fun leadingSeparators(rest: String): String = rest.takeWhile { it == '/' || it == '\\' }

/**
 * The file path to open for an unquoted [PATH_PATTERN] match. [raw] is group 1 — the full path *including*
 * its extension — and [ext] is group 2 (the bare extension, or null when the match has no extension but a
 * `:line`). [raw] already carries the extension, so it is returned unchanged: never re-append `.` + [ext],
 * or [resolve] would look for a doubled `Foo.kt.kt` and find nothing.
 */
internal fun fileLinkTarget(raw: String, ext: String?): String = raw

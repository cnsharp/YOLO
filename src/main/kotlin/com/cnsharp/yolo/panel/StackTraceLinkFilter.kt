package com.cnsharp.yolo.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File
import java.util.regex.Pattern

/**
 * Makes stack-trace / traceback file references clickable — the cases [FileLinkFilter] does not cover
 * because the file name has no directory component.
 *
 * Handles:
 *  - Java/Kotlin frames: `at com.foo.Bar.method(Bar.java:123)` → links `Bar.java:123`.
 *  - Same-directory references: `Bar.kt:12`.
 *  - Python/JS tracebacks: `File "app/main.py", line 42` and `File 'app/main.py', line 42`.
 *  - Bare file names with no line number, e.g. `plugin.xml`, `build.gradle.kts`, `README.md` — linked
 *    on their own so a log line like "Updated plugin.xml" is clickable.
 *
 * A bare file name is resolved by searching the project's content roots and the filename index, so it
 * opens the right file even when only the base name is printed. Clicking hides the YOLO pane.
 *
 * Public APIs only: JediTerm's [HyperlinkFilter] for the link and IntelliJ's [FilenameIndex] /
 * [com.intellij.openapi.fileEditor.OpenFileDescriptor] for resolution/navigation.
 */
class StackTraceLinkFilter(
    private val project: Project,
    private val baseDir: String
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank()) return null
        val items = mutableListOf<LinkResultItem>()
        for (spec in SPECS) {
            val matcher = spec.pattern.matcher(text)
            var guard = 0
            while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
                val raw = matcher.group(spec.fileGroup)
                val line = if (spec.lineGroup >= 0) matcher.group(spec.lineGroup)?.toIntOrNull() else null
                val column = if (spec.colGroup >= 0) matcher.group(spec.colGroup)?.toIntOrNull() else null
                val file = resolve(raw) ?: continue
                val link = yoloHyperlink(project) { openFileAt(project, file, line, column) }
                items.add(LinkResultItem(matcher.start(), matcher.end(), link))
            }
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a file name (possibly with directories) against the working dir, content roots, and the filename index. */
    private fun resolve(raw: String): File? {
        val base = File(baseDir, raw)
        if (base.isFile) return base
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val f = File(root.path, raw)
            if (f.isFile) return f
        }
        // Absolute / already-rooted path.
        val abs = File(raw)
        if (abs.isFile) return abs
        // Bare file name: find by name across the project's indexed files.
        if (!raw.contains('/') && !raw.contains('\\')) {
            for (vf in FilenameIndex.getVirtualFilesByName(raw, GlobalSearchScope.projectScope(project))) {
                if (!vf.isDirectory) return File(vf.path)
            }
        }
        return null
    }

    companion object {
        private const val MAX_MATCHES_PER_LINE = 50

        private data class Spec(val pattern: Pattern, val fileGroup: Int, val lineGroup: Int, val colGroup: Int)

        /** Bare `FileName.ext:line` / `FileName.ext:line:col` (no directory component). */
        private val BARE: Pattern = Pattern.compile("""(?<![\\/\w.])([\w.\-]+\.\w+):(\d+)(?::(\d+))?""")

        /** Bare file name with no line number, e.g. `plugin.xml`, `build.gradle.kts` — only the base name.
         *  Requires a letter extension (so version numbers like `1.0` are skipped) and a trailing boundary
         *  so it does not grab the start of a longer path or a `name:line` reference. */
        private val BARE_NAME: Pattern = Pattern.compile("""(?<![\\/\w.])([\w.\-]+\.[A-Za-z]{1,12})(?![\\/\w.:])""")

        /** Python `File "path", line N` (double-quoted). */
        private val PY_DQ: Pattern = Pattern.compile("""File "([^"]+\.\w+)", line (\d+)""")

        /** Python `File 'path', line N` (single-quoted). */
        private val PY_SQ: Pattern = Pattern.compile("""File '([^']+\.\w+)', line (\d+)""")

        private val SPECS = listOf(
            Spec(BARE, fileGroup = 1, lineGroup = 2, colGroup = 3),
            Spec(PY_DQ, fileGroup = 1, lineGroup = 2, colGroup = -1),
            Spec(PY_SQ, fileGroup = 1, lineGroup = 2, colGroup = -1),
            Spec(BARE_NAME, fileGroup = 1, lineGroup = -1, colGroup = -1),
        )
    }
}

package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
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
 * opens the right file even when only the base name is printed.
 *
 * A bare file name is **not** linked when it is the tail of a truncated path — i.e. immediately preceded
 * by a `…`/`...` abbreviation marker (e.g. `Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)`).
 * The agent only omitted the middle of the path; the fragment after the marker is not a file on its own,
 * so it must not become a clickable (and wrong) link. See [isTruncatedPathHead].
 *
 * Public APIs only: the platform's [Filter] for the link and IntelliJ's [FilenameIndex] /
 * `OpenFileDescriptor` for resolution/navigation.
 */
class StackTraceLinkFilter(
    private val project: Project?,
    private val baseDir: String,
    private val wrapState: PathWrapState? = null
) : Filter {

    override fun applyFilter(text: String, entireLength: Int): Filter.Result? {
        if (text.isBlank() || isDiffLine(text)) return null
        val items = mutableListOf<Filter.ResultItem>()
        val continuationSpan = wrapState?.continuationSpan
        for (spec in SPECS) {
            val matcher = spec.pattern.matcher(text)
            var guard = 0
            while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
                val raw = matcher.group(spec.fileGroup)
                // A `…`/`...` truncation marker immediately before the file name means this match is the tail
                // of a truncated path (e.g. `Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)`),
                // not a real bare-file reference — don't link it. The agent only abbreviated the path; the
                // fragment after the marker is not a file on its own.
                if (isTruncatedPathHead(text, matcher.start(spec.fileGroup))) continue
                // If FileLinkFilter already reconstructed the wrapped path and created a link covering
                // this match's span, skip it to avoid a duplicate (and incorrect bare-name) link.
                if (continuationSpan != null
                    && matcher.start() < continuationSpan.last
                    && matcher.end() > continuationSpan.first) continue
                val line = if (spec.lineGroup >= 0) matcher.group(spec.lineGroup)?.toIntOrNull() else null
                val column = if (spec.colGroup >= 0) matcher.group(spec.colGroup)?.toIntOrNull() else null
                // Resolution (FilenameIndex / content roots) is deferred to click time so streaming output is
                // never blocked by index queries on the filtering thread.
                val link = expLink {
                    val file = resolve(raw) ?: return@expLink null
                    val p = project ?: return@expLink null
                    { openFileAt(p, file, line, column) }
                }
                items.add(Filter.ResultItem(matcher.start(), matcher.end(), link, HIGHLIGHT_ATTRS))
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /** Resolve a file name (possibly with directories) against the working dir, content roots, and the filename
     *  index. Called from the click handler (EDT), wrapped in a read action because FilenameIndex / the
     *  project model require one. */
    private fun resolve(raw: String): File? {
        val base = File(baseDir, raw)
        if (base.isFile) return base
        // Content-root + filename-index resolution needs a live project; without one (e.g. offline unit
        // tests) we still resolve against the working dir and absolute paths below.
        val project = this.project
        if (project != null) {
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                val f = File(root.path, raw)
                if (f.isFile) return f
            }
            // Bare file name: find by name across the project's indexed files.
            if (!raw.contains('/') && !raw.contains('\\')) {
                for (vf in FilenameIndex.getVirtualFilesByName(raw, GlobalSearchScope.projectScope(project))) {
                    if (!vf.isDirectory) return File(vf.path)
                }
            }
        }
        // Absolute / already-rooted path.
        val abs = File(raw)
        if (abs.isFile) return abs
        return null
    }

    companion object {
        private data class Spec(val pattern: Pattern, val fileGroup: Int, val lineGroup: Int, val colGroup: Int)

        private val SPECS = listOf(
            Spec(STACK_BARE_PATTERN, fileGroup = 1, lineGroup = 2, colGroup = 3),
            Spec(STACK_PY_DQ_PATTERN, fileGroup = 1, lineGroup = 2, colGroup = -1),
            Spec(STACK_PY_SQ_PATTERN, fileGroup = 1, lineGroup = 2, colGroup = -1),
            Spec(STACK_BARE_NAME_PATTERN, fileGroup = 1, lineGroup = -1, colGroup = -1),
        )
    }
}

package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ReadAction
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
        if (text.isBlank() || isDiffLine(text)) return null
        val items = mutableListOf<LinkResultItem>()
        for (spec in SPECS) {
            val matcher = spec.pattern.matcher(text)
            var guard = 0
            while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
                val raw = matcher.group(spec.fileGroup)
                val line = if (spec.lineGroup >= 0) matcher.group(spec.lineGroup)?.toIntOrNull() else null
                val column = if (spec.colGroup >= 0) matcher.group(spec.colGroup)?.toIntOrNull() else null
                // Resolution (FilenameIndex / content roots) is deferred to click time so streaming output is
                // never blocked by index queries on the terminal emulator thread.
                val link = yoloHyperlink(project) {
                    val file = resolve(raw) ?: return@yoloHyperlink
                    openFileAt(project, file, line, column)
                }
                items.add(LinkResultItem(matcher.start(), matcher.end(), link))
            }
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a file name (possibly with directories) against the working dir, content roots, and the filename
     *  index. Called from the click handler (EDT), wrapped in a read action because FilenameIndex / the
     *  project model require one. */
    private fun resolve(raw: String): File? = ReadAction.compute<File?, Throwable> {
        val base = File(baseDir, raw)
        if (base.isFile) return@compute base
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val f = File(root.path, raw)
            if (f.isFile) return@compute f
        }
        // Absolute / already-rooted path.
        val abs = File(raw)
        if (abs.isFile) return@compute abs
        // Bare file name: find by name across the project's indexed files.
        if (!raw.contains('/') && !raw.contains('\\')) {
            for (vf in FilenameIndex.getVirtualFilesByName(raw, GlobalSearchScope.projectScope(project))) {
                if (!vf.isDirectory) return@compute File(vf.path)
            }
        }
        null
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

package com.cnsharp.yolo.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File
import java.util.regex.Pattern

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
    private val project: Project,
    private val baseDir: String
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank()) return null
        val items = mutableListOf<LinkResultItem>()

        // Standard (unquoted) path references.
        val m = PATH_PATTERN.matcher(text)
        while (m.find()) {
            val raw = m.group(1)
            val line = m.group(2)?.toIntOrNull()
            val column = m.group(4)?.toIntOrNull()
            val file = resolve(raw) ?: continue
            val link = yoloHyperlink(project) { openFileAt(project, file, line, column) }
            // Span the whole reference (path + optional :line:column) so a click anywhere navigates.
            items.add(LinkResultItem(m.start(1), m.end(), link))
        }

        // Quoted paths (may contain spaces).
        val q = QUOTED_PATTERN.matcher(text)
        while (q.find()) {
            val raw = q.group(2)
            val line = q.group(3)?.toIntOrNull()
            val column = q.group(4)?.toIntOrNull()
            val file = resolve(raw) ?: continue
            val link = yoloHyperlink(project) { openFileAt(project, file, line, column) }
            items.add(LinkResultItem(q.start(2), q.end(), link))
        }

        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a possibly-relative path against the agent's working dir and the project's content roots. */
    private fun resolve(raw: String): File? {
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
         * A path token must contain at least one separator, optionally start with a Windows drive letter,
         * end with `<name>.<ext>` (the extension is optional so no-extension files like `Makefile`/`Dockerfile`
         * are supported), and may be followed by `:line`, `:line-line` (range) and/or `:column`. A leading
         * slash/word/dot is excluded so the pattern never reaches into a `http(s)://` URL's own path.
         */
        private val PATH_PATTERN: Pattern = Pattern.compile(
            """(?<![\\/\w.])((?:\b[A-Za-z]:)?[\\/]?(?:[^\\/:*?"<>|\s]+[\\/])+[^\\/:*?"<>|\s]+(?:\.\w+)?)(?::(\d+))?(?:-(\d+))?(?::(\d+))?"""
        )

        /** Quoted path (allows embedded spaces); requires an absolute-ish path (starts with `/` or a drive). */
        private val QUOTED_PATTERN: Pattern = Pattern.compile(
            """(["'])((?:[A-Za-z]:)?[\\/][^"']*?\.\w+)\1(?::(\d+))?(?::(\d+))?"""
        )
    }
}

package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File
import java.util.regex.Pattern

/**
 * Makes file references printed by agents clickable in the embedded terminal.
 *
 * Matches references of the form `path`, `path:line`, or `path:line:column` — e.g.
 * `src/foo/Bar.kt:42`, `/abs/Bar.kt:42:13`, `C:\foo\Bar.kt:7` — and, when the file actually exists
 * in the project, renders them as a hyperlink. Clicking opens the file in the IDE editor at the
 * referenced line/column.
 *
 * Built entirely on public APIs: JediTerm's [HyperlinkFilter] / [LinkInfo] for the terminal link, and
 * IntelliJ's [OpenFileDescriptor] / [FileEditorManager] for navigation — so it stays Marketplace-safe.
 */
class FileLinkFilter(
    private val project: Project,
    private val baseDir: String
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank()) return null
        val items = mutableListOf<LinkResultItem>()
        val matcher = PATH_PATTERN.matcher(text)
        while (matcher.find()) {
            val rawPath = matcher.group(1)
            val line = matcher.group(2)?.toIntOrNull()
            val column = matcher.group(3)?.toIntOrNull()
            val file = resolve(rawPath) ?: continue
            val link = yoloHyperlink(project) { open(file, line, column) }
            // Span the whole reference (path + optional :line:column) so a click anywhere navigates.
            items.add(LinkResultItem(matcher.start(1), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a possibly-relative path against the agent's working dir and the project's content roots. */
    private fun resolve(raw: String): File? {
        val candidates = mutableListOf<File>()
        candidates += File(raw)
        candidates += File(baseDir, raw)
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            candidates += File(root.path, raw)
        }
        return candidates.firstOrNull { it.isFile }
    }

    private fun open(file: File, line: Int?, column: Int?) {
        ReadAction.run<Throwable> {
            val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@run
            // OpenFileDescriptor uses 0-based line/column; agent output is 1-based.
            val descriptor = OpenFileDescriptor(project, vFile, (line ?: 1) - 1, (column ?: 1) - 1)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    companion object {
        /**
         * A path token must contain at least one separator, optionally start with a Windows drive letter,
         * and end with `<name>.<ext>`. It may be followed by `:line` and `:column`.
         */
        private val PATH_PATTERN: Pattern = Pattern.compile(
            """((?:\b[A-Za-z]:)?[\\/]?(?:[^\\/:*?"<>|\s]+[\\/])+[^\\/:*?"<>|\s]+\.\w+)(?::(\d+))?(?::(\d+))?"""
        )
    }
}

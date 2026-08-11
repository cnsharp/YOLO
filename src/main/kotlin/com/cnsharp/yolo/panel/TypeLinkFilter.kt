package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.util.regex.Pattern

/**
 * Makes type references printed by agents clickable in the embedded terminal.
 *
 * Two kinds of references are linked:
 *  - **Qualified names** (e.g. `com.foo.Bar`, `com.foo.Bar.Baz` for inner classes): resolved across the
 *    whole project, including library/JDK sources, since a fully-qualified name is unambiguous.
 *  - **Simple names** (e.g. `Bar`): resolved *only* to classes that live inside the project's content
 *    roots, so ubiquitous JDK/library types like `String` or `List` are deliberately not linked.
 *
 * A trailing lowercase extension (e.g. `Bar.kt`) is excluded so these links never collide with the
 * file-path links produced by [FileLinkFilter].
 *
 * Resolution uses only public PSI APIs ([JavaPsiFacade] / [PsiShortNamesCache]) and is skipped while the
 * index is in dumb mode, so it stays Marketplace-safe and never blocks the terminal.
 */
class TypeLinkFilter(private val project: Project) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || DumbService.isDumb(project)) return null
        val items = mutableListOf<LinkResultItem>()
        val matcher = TYPE_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val qualified = matcher.group("qualified")
            val simple = matcher.group("simple")
            val target: PsiElement? = if (qualified != null) {
                resolveQualified(qualified)
            } else if (simple != null) {
                resolveSimple(simple)
            } else {
                null
            }
            if (target == null) continue
            val link = yoloHyperlink(project) { navigateTo(target) }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    private fun resolveQualified(fqn: String): PsiElement? = ReadAction.compute<PsiElement?, Throwable> {
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
    }

    /** Simple names link only to project classes, to avoid underlining every `String`/`List` in the output. */
    private fun resolveSimple(name: String): PsiElement? = ReadAction.compute<PsiElement?, Throwable> {
        val cache = PsiShortNamesCache.getInstance(project)
        val classes = cache.getClassesByName(name, GlobalSearchScope.allScope(project))
        val inProject = ProjectRootManager.getInstance(project).fileIndex
        classes.firstOrNull { cls ->
            cls.containingFile?.virtualFile?.let { inProject.isInContent(it) } == true
        }
    }

    /** Open the PSI element's declaration in the editor at its offset (0-based line/column). */
    private fun navigateTo(target: PsiElement) {
        ReadAction.run<Throwable> {
            val vFile = target.containingFile?.virtualFile ?: return@run
            val offset = target.textOffset
            val doc = FileDocumentManager.getInstance().getDocument(vFile)
            val (line, col) = if (doc != null) {
                val l = doc.getLineNumber(offset)
                l to (offset - doc.getLineStartOffset(l))
            } else {
                0 to 0
            }
            val descriptor = OpenFileDescriptor(project, vFile, line, col)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    companion object {
        private const val MAX_MATCHES_PER_LINE = 50

        /**
         * Either a qualified name (lowercase package segments + capitalized class/inner segments) or a
         * simple capitalized identifier not preceded by a word char or dot. Both exclude a trailing
         * lowercase extension (e.g. `.kt`) so they don't overlap file-path links.
         */
        private val TYPE_PATTERN: Pattern = Pattern.compile(
            """(?<qualified>(?:[a-z][a-zA-Z0-9_]*\.)+(?:[A-Z][a-zA-Z0-9_]*(?:\.[A-Z][a-zA-Z0-9_]*)*))(?!\.[a-z])""" +
                """|(?<simple>(?<![.\w])[A-Z][a-zA-Z0-9_]*)(?!\.[a-z])"""
        )
    }
}

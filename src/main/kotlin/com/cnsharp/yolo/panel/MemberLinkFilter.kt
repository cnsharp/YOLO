package com.cnsharp.yolo.panel

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.util.regex.Pattern

/**
 * Makes `Class.member` / `Class#member` references clickable, navigating to the specific method, field,
 * or inner class rather than just the enclosing class declaration.
 *
 * Examples: `com.foo.Bar.baz`, `Bar#findById`, `UserRepository.save`. The class part is resolved with the
 * same public PSI APIs used by [TypeLinkFilter] (qualified names across the whole project; simple names
 * only within project content roots). If the member can't be found, the link falls back to the class
 * declaration. Clicking hides the YOLO pane.
 *
 * Skipped while the index is in dumb mode so it never blocks the terminal.
 */
class MemberLinkFilter(private val project: Project) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || DumbService.isDumb(project)) return null
        val items = mutableListOf<LinkResultItem>()
        val matcher = MEMBER_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val classRef = matcher.group("class") ?: continue
            val member = matcher.group("member") ?: continue
            val psiClass = if (classRef.contains('.')) {
                resolveQualifiedClass(project, classRef)
            } else {
                resolveSimpleClass(project, classRef)
            } ?: continue
            val target: PsiElement = findMember(psiClass, member) ?: psiClass
            val link = yoloHyperlink(project) { openElementAt(project, target) }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    companion object {
        private const val MAX_MATCHES_PER_LINE = 50

        /**
         * A class reference (qualified name, or a simple capitalized identifier not preceded by a word/dot)
         * followed by `.` or `#` and a member name. A trailing lowercase extension is excluded so file-path
         * links are not mistaken for member references.
         */
        private val MEMBER_PATTERN: Pattern = Pattern.compile(
            """(?<class>(?:[a-z][a-zA-Z0-9_]*\.)+(?:[A-Z][a-zA-Z0-9_]*(?:\.[A-Z][a-zA-Z0-9_]*)*)|(?<![.\w])[A-Z][a-zA-Z0-9_]*)[.#](?<member>(?<![.\w])[A-Za-z_]\w*)"""
        )
    }
}

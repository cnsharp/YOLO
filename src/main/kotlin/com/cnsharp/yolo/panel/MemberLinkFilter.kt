package com.cnsharp.yolo.panel

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem

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
        if (text.isBlank() || isDiffLine(text) || DumbService.isDumb(project)) return null
        // Only link references whose class part is a real project type (see [YoloProjectTypes]), so a
        // shortcut notation like `Ctrl/C` — where `Ctrl` is not a class — is not highlighted.
        val types = YoloProjectTypes.snapshot(project)
        val items = mutableListOf<LinkResultItem>()
        val matcher = MEMBER_REF_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val classRef = matcher.group("class") ?: continue
            val member = matcher.group("member") ?: continue
            val known = if (classRef.contains('.')) types.containsQualified(classRef) else types.containsSimple(classRef)
            if (!known) continue
            // Resolution is deferred to click time so streaming output is never blocked by PSI index queries
            // on the terminal emulator thread. The link navigates only if the class/member resolves.
            val link = yoloHyperlink(project) {
                val psiClass = if (classRef.contains('.')) {
                    resolveQualifiedClass(project, classRef)
                } else {
                    resolveSimpleClass(project, classRef)
                } ?: return@yoloHyperlink
                // Land on the member only when it is declared in the project (incl. a project base class);
                // an inherited member from a library/JDK class (e.g. CustomerException#getMessage from
                // Throwable) would otherwise jump into the JDK, so fall back to the referenced class instead.
                val member = findMember(psiClass, member)
                val target: PsiElement = if (member != null && isInProjectContent(member, project)) member else psiClass
                openElementAt(project, target)
            }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}

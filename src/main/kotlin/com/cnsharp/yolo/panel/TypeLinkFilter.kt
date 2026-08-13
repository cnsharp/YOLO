package com.cnsharp.yolo.panel

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem

/**
 * Makes type references printed by agents clickable in the embedded terminal.
 *
 * Two kinds of references are linked:
 *  - **Qualified names** (e.g. `com.foo.Bar`, `com.foo.Bar.Baz` for inner classes): resolved across the
 *    whole project, including library/JDK sources, since a fully-qualified name is unambiguous.
 *  - **Simple names** (e.g. `Bar`): linked *only* when the name is a real type inside the project's content
 *    roots (see [YoloProjectTypes]), so ubiquitous JDK/library types like `String` or `List` — and ordinary
 *    capitalized words like `Result` or `OK` — are deliberately not highlighted.
 *
 * A trailing lowercase extension (e.g. `Bar.kt`) is excluded so these links never collide with the
 * file-path links produced by [FileLinkFilter].
 *
 * Resolution uses only public PSI APIs ([com.intellij.psi.JavaPsiFacade] / [com.intellij.psi.search.PsiShortNamesCache])
 * and is skipped while the index is in dumb mode, so it stays Marketplace-safe and never blocks the terminal.
 */
class TypeLinkFilter(private val project: Project) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text) || DumbService.isDumb(project)) return null
        // Only link names that are real project types, so ordinary capitalized words and shortcut
        // notations (e.g. the `Ctrl` in `Ctrl/C`) are not painted blue.
        val types = YoloProjectTypes.snapshot(project)
        val items = mutableListOf<LinkResultItem>()
        val matcher = TYPE_NAME_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val qualified = matcher.group("qualified")
            val simple = matcher.group("simple")
            val known = when {
                qualified != null -> types.containsQualified(qualified)
                simple != null -> types.containsSimple(simple)
                else -> false
            }
            if (!known) continue
            // Resolution is deferred to click time so streaming output is never blocked by PSI index queries
            // on the terminal emulator thread. The link navigates only if the name resolves to a real class.
            val link = yoloHyperlink(project) {
                val target = if (qualified != null) {
                    resolveQualifiedClass(project, qualified)
                } else if (simple != null) {
                    resolveSimpleClass(project, simple)
                } else {
                    null
                }
                if (target != null) openElementAt(project, target)
            }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}

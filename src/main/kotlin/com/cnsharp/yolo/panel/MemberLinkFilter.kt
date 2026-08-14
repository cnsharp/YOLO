package com.cnsharp.yolo.panel

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem

/**
 * Makes `Class.member` / `Class#member` references clickable, navigating to the specific method, field,
 * or inner class rather than just the enclosing class declaration.
 *
 * Examples: `com.foo.Bar.baz`, `Bar#findById`, `UserRepository.save`, and across languages
 * `MyApp.Services.UserService.SomeMethod` (C#), `myapp.models.User.save` (Python), `http.Client.Get` (Go).
 *
 * Resolution is language-agnostic: the class part is resolved through the `gotoClassContributor` EP (the
 * same mechanism [TypeLinkFilter] uses), and the member is resolved best-effort through the
 * `gotoSymbolContributor` EP. If the member can't be pinned down (some non-JVM languages expose members
 * less precisely than Java), the link falls back to the class declaration. Clicking hides the YOLO pane.
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
            val known = if (isQualifiedName(classRef)) types.containsQualified(classRef) else types.containsSimple(classRef)
            if (!known) continue
            // Resolution is deferred to click time so streaming output is never blocked by PSI index queries
            // on the terminal emulator thread. The link navigates only if the class/member resolves.
            val link = yoloHyperlink(project) {
                val classItem = resolveType(project, classRef) ?: return@yoloHyperlink
                // Try to land on the member; fall back to the class declaration if it can't be pinned down.
                val memberItem = resolveMember(project, classItem, member)
                if (memberItem != null) openNavigationItem(memberItem) else openNavigationItem(classItem)
            }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}

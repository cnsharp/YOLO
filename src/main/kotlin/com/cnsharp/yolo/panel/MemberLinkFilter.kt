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
 * Hard-wrapped references are reconstructed: a `Class` split across physical lines (the terminal broke the
 * line at its width) is stitched back together via the shared [WrapState] — e.g. `com.foo.Ba` + `r#method`
 * becomes `com.foo.Bar#method` and links to the method. The reconstructed continuation tail is also reported
 * to [StackTraceLinkFilter] (via [WrapState.continuationSpan]) so a stray `…`/extension match overlapping it
 * is suppressed.
 *
 * Skipped while the index is in dumb mode so it never blocks the terminal.
 */
class MemberLinkFilter(
    private val project: Project?,
    private val wrapState: WrapState? = null,
    private val typesProvider: (Project?) -> YoloProjectTypes.Snapshot = { p ->
        if (p != null) YoloProjectTypes.snapshot(p) else YoloProjectTypes.Snapshot(emptySet(), emptySet())
    },
    /**
     * Test-only hook: when non-null, the `class`/`member` pair a generated link targets is reported here
     * synchronously (once per linked reference, at link-generation time). Lets offline tests assert the
     * *reference* a reconstructed (hard-wrapped) link would resolve, without a live project/index — PSI
     * navigation itself stays deferred to click time and is exercised only in a real IDE. Production passes `null`.
     */
    private val onResolved: ((classRef: String, member: String) -> Unit)? = null,
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text) || (project != null && DumbService.isDumb(project))) return null
        // Only link references whose class part is a real project type (see [YoloProjectTypes]), so a
        // shortcut notation like `Ctrl/C` — where `Ctrl` is not a class — is not highlighted.
        val types = typesProvider(project)
        val items = mutableListOf<LinkResultItem>()

        // --- Hard-wrap reconstruction: stitch a `Class` split across physical lines. ---
        val prefix = wrapState?.pendingMember ?: ""
        val prefixHasMember = wrapState?.pendingMemberHasMember ?: false
        wrapState?.pendingMember = ""
        wrapState?.pendingMemberHasMember = false
        if (prefix.isNotEmpty()) {
            val trimmed = text.trimStart()
            val leading = text.length - trimmed.length
            val combined = prefix + trimmed
            val cm = MEMBER_REF_PATTERN.matcher(combined)
            var g = 0
            while (cm.find() && g++ < MAX_MATCHES_PER_LINE) {
                val mStart = cm.start()
                val mEnd = cm.end()
                // Only the match spanning the prefix/continuation boundary is a reconstruction.
                if (mStart >= prefix.length || mEnd <= prefix.length) continue
                val classRef = cm.group("class") ?: continue
                val member = cm.group("member") ?: continue
                if (prefixHasMember) {
                    // The head already carried its `#member` part, so the wrap split *inside* the member name:
                    // the continuation must be a plain identifier remainder of that same name (`udConfirm`,
                    // `Deduct`). A next line that begins its own reference (`UserService.find`) is a NEW token,
                    // not a wrap remainder — gluing it on would produce a bogus `…#saveUserService` link.
                    // Detect that by the separator that follows the continuation's leading identifier.
                    val lead = trimmed.takeWhile { it.isLetterOrDigit() || it == '_' }
                    val after = trimmed.substring(lead.length)
                    if (lead.isEmpty() || after.startsWith('.') || after.startsWith('#')) continue
                }
                val known = if (isQualifiedName(classRef)) {
                    lastTypeNameSegment(classRef) in types.simple
                } else {
                    types.containsSimple(classRef)
                }
                if (!known) continue
                val tailStart = leading
                val tailEnd = leading + (mEnd - prefix.length)
                if (tailEnd > text.length) continue
                // Record the completion: the head line's link was created earlier, before this wrap was known.
                // It looks the member up here at click time so it lands on the real method instead of falling
                // back to the class (a truncated `Foo#antiFra` head would otherwise only open the class).
                wrapState?.completedMembers?.set(prefix, member)
                // Report the resolved reference (test seam) synchronously — PSI navigation itself is deferred
                // to click time, but the class/member this link targets is known here.
                onResolved?.invoke(classRef, member)
                // Resolution runs on click so streaming output is never blocked by PSI index queries on the
                // terminal emulator thread. The link navigates only if the class/member resolves.
                val link = yoloHyperlink(project) {
                    val p = project ?: return@yoloHyperlink
                    val classItem = resolveType(p, classRef) ?: return@yoloHyperlink
                    // A stitched link is member-targeted on purpose: it is the tail of a wrapped reference, so
                    // it only navigates when the member really resolves. Falling back to the class here would
                    // let a mis-detected wrap (a new token on the next line) jump to an unrelated class.
                    val memberItem = resolveMember(p, classItem, member)
                    if (memberItem != null) openNavigationItem(memberItem)
                }
                items.add(LinkResultItem(tailStart, tailEnd, link))
                wrapState?.continuationSpan = tailStart until tailEnd
            }
        }
        // --- End hard-wrap reconstruction ---

        val matcher = MEMBER_REF_PATTERN.matcher(text)
        var guard = 0
        var linkedThisLine = false
        val cont = wrapState?.continuationSpan
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            // Skip a match overlapping the reconstructed continuation tail (above) to avoid a duplicate span.
            if (cont != null && matcher.start() < cont.last && matcher.end() > cont.first) continue
            val classRef = matcher.group("class") ?: continue
            val member = matcher.group("member") ?: continue
            // Gate the class part: a qualified ref is linked when its trailing segment is a known project
            // type (cheap, derived from the simple-name set); a simple ref must be a known project type.
            val known = if (isQualifiedName(classRef)) {
                lastTypeNameSegment(classRef) in types.simple
            } else {
                types.containsSimple(classRef)
            }
            if (!known) continue
            val refText = text.substring(matcher.start(), matcher.end())
            // Report the resolved reference (test seam) synchronously — PSI navigation itself is deferred to
            // click time, but the class/member this link targets is known here.
            onResolved?.invoke(classRef, member)
            // A reference reaching end-of-line may be a terminal hard-wrap that split it INSIDE the member name
            // (`Foo#antiFra` + `udConfirm`). Hand it to the next physical line so it can be stitched back into
            // one reference; the link is still created here (a complete single-line `Class#method` must keep
            // working), and it resolves the completed member if the next line turns out to continue it.
            if (matcher.end() >= text.trimEnd().length) {
                wrapState?.pendingMember = refText
                wrapState?.pendingMemberHasMember = true
            }
            // Resolution runs on click so streaming output is never blocked by PSI index queries on the
            // terminal emulator thread. The link navigates only if the class/member resolves.
            val link = yoloHyperlink(project) {
                val p = project ?: return@yoloHyperlink
                val classItem = resolveType(p, classRef) ?: return@yoloHyperlink
                // If the continuation line completed this member, navigate to the full name — otherwise the
                // truncated head would miss the member and fall back to the class declaration.
                val resolved = wrapState?.completedMembers?.get(refText) ?: member
                // Try to land on the member; fall back to the class declaration if it can't be pinned down.
                val memberItem = resolveMember(p, classItem, resolved)
                if (memberItem != null) openNavigationItem(memberItem) else openNavigationItem(classItem)
            }
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
            linkedThisLine = true
        }

        // Hard-wrap head detection: if a `Class` reaches end-of-line with no `#member` / `.member` yet,
        // remember it so the next physical line can reconstruct (e.g. `Foo.impl.Ba` + `r#method`, or a wrap
        // that split right before the `#`: `Foo.Bar` + `#method`). Skip when this line already linked a
        // complete member ref — that case hands the reference over in the loop above.
        //
        // The class is stashed whether or not it is a known type: a bare `Class` is never a member-link
        // target on its own, so holding it costs nothing, and the next line can only stitch it if it starts
        // with a `#` separator (a plain continuation like `next words` simply doesn't match).
        if (!linkedThisLine) {
            val head = MEMBER_HEAD_PATTERN.matcher(text)
            if (head.find()) {
                val headClass = head.group("class")
                if (headClass != null && headClass.isNotEmpty()) {
                    wrapState?.pendingMember = headClass
                    wrapState?.pendingMemberHasMember = false
                }
            }
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}

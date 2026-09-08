package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for hard-wrap reconstruction of *type* and *member* references — the cases the path-only
 * [WrapState] handling missed. When the terminal wraps a reference at its column width, the head fragment lands
 * on one physical line and the rest on the next. [TypeLinkFilter] / [MemberLinkFilter] stitch the two lines back
 * together via [WrapState] so the reconstructed reference links to the right target (class or method).
 *
 * Runs offline (null project + injected [YoloProjectTypes] snapshot) like the other panel filter tests: the
 * click handler (resolution) is never invoked here, so only the *matching* is asserted.
 */
class ReferenceWrapTest {

    private val types = YoloProjectTypes.Snapshot(simple = setOf("LoanOrderServiceImpl", "Bar"))

    // ---- Member reference wrapped across lines ----
    // com.cnsharp...LoanOrderServiceIm  +  pl#antiFraudConfirm  ->  LoanOrderServiceImpl#antiFraudConfirm

    @Test
    fun testMemberWrapHeadStoresPendingPrefix() {
        val state = WrapState()
        val filter = MemberLinkFilter(null, state, { _ -> types })
        val result = filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        // Head fragment is incomplete (no #member) — must not link on its own, but must be remembered.
        assertTrue(result == null || result.items.isEmpty())
        assertEquals("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm", state.pendingMember)
    }

    @Test
    fun testMemberWrapReconstructedOnContinuationLine() {
        // The reported regression: the wrapped `Class#member` must link as one tail on the continuation line.
        val state = WrapState()
        val filter = MemberLinkFilter(null, state, { _ -> types })
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        val cont = "pl#antiFraudConfirm"
        val linked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals(listOf("pl#antiFraudConfirm"), linked)
    }

    @Test
    fun testMemberWrapSuppressesStrayBareNameLink() {
        // The continuation `pl#antiFraudConfirm` looks like a `.pl` (Perl) file to StackTraceLinkFilter.
        // Because MemberLinkFilter reconstructed the wrapped reference and set continuationSpan, that stray
        // bare-name link must be suppressed — otherwise only `pl` would appear as a (wrong) link.
        val state = WrapState()
        val member = MemberLinkFilter(null, state, { _ -> types })
        member.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        val cont = "pl#antiFraudConfirm"
        member.apply(cont) // sets continuationSpan covering the whole continuation line
        val stack = StackTraceLinkFilter(null, "/tmp/agent-working-dir", state)
        val sr = stack.apply(cont)
        assertTrue("stray .pl bare-name link must be suppressed", sr == null || sr.items.isEmpty())
    }

    // ---- Type reference wrapped across lines ----
    // com.cnsharp...LoanOrderServiceIm  +  pl  ->  LoanOrderServiceImpl

    @Test
    fun testTypeWrapReconstructedOnContinuationLine() {
        val state = WrapState()
        val filter = TypeLinkFilter(null, { _ -> types }, state)
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        val cont = "pl"
        val linked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals(listOf("pl"), linked)
    }

    @Test
    fun testTypeWrapResolvesToClassName() {
        // Real verification for the type wrap: the reconstructed link must target `LoanOrderServiceImpl`
        // (the stitched class), not just produce a span. Fired synchronously via the onResolved seam.
        val state = WrapState()
        var captured: String? = null
        val filter = TypeLinkFilter(null, { _ -> types }, state) { captured = it }
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        filter.apply("pl")
        assertEquals("com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl", captured)
    }

    @Test
    fun testMemberWrapWithLeadingWhitespaceOnContinuation() {
        // Continuation lines may carry leading indentation; the tail link must still map to the visible text.
        val state = WrapState()
        val filter = MemberLinkFilter(null, state, { _ -> types })
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        val cont = "    pl#antiFraudConfirm"
        val linked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals(listOf("pl#antiFraudConfirm"), linked)
    }

    @Test
    fun testMemberWrapResolvesToMethodRef() {
        // Real verification the user asked for: the reconstructed link must target the *method*
        // `LoanOrderServiceImpl#antiFraudConfirm`, not merely produce a span. The onResolved test seam reports
        // the class/member the link will navigate to — fired synchronously at link-generation time, so it runs
        // offline without a live PSI index (which only exists in a real IDE).
        val state = WrapState()
        var captured: Pair<String, String>? = null
        val filter = MemberLinkFilter(null, state, { _ -> types }) { c, m -> captured = c to m }
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        filter.apply("pl#antiFraudConfirm")
        assertEquals("com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl" to "antiFraudConfirm", captured)
    }

    @Test
    fun testMemberWrapResolvesToMethodRefWithIndent() {
        // Same as above but with leading indentation on the continuation — the resolved reference must be
        // identical (indentation is visual only, not part of the reconstructed class/member).
        val state = WrapState()
        var captured: Pair<String, String>? = null
        val filter = MemberLinkFilter(null, state, { _ -> types }) { c, m -> captured = c to m }
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceIm")
        filter.apply("    pl#antiFraudConfirm")
        assertEquals("com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl" to "antiFraudConfirm", captured)
    }

    @Test
    fun testSingleLineMemberRefStillResolvesToMethod() {
        // No-regression guard: a complete `Class#method` on ONE line must still link to the method. Deferring
        // every end-of-line member ref (to catch wraps) would silently drop this.
        val state = WrapState()
        var captured: Pair<String, String>? = null
        val filter = MemberLinkFilter(null, state, { _ -> types }) { c, m -> captured = c to m }
        filter.apply("com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl#triggerInnerCardGroup2FirstDeduct")
        assertEquals(
            "com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl" to "triggerInnerCardGroup2FirstDeduct",
            captured
        )
    }

    @Test
    fun testMemberWrapSplitInsideMemberNameResolvesToFullMethod() {
        // The wrap split INSIDE the method name (`…#triggerInnerCardGroup2First` + `Deduct`). The continuation
        // must stitch the full name, and the head link must be completed too — otherwise clicking the head
        // (the visible, truncated link) only opens the class instead of the method.
        // The wrap split INSIDE the method name (`…#triggerInnerCardGroup2First` + `Deduct`): both wrapped rows
        // are highlighted, and both navigate to the same complete method.
        val state = WrapState()
        val resolved = mutableListOf<Pair<String, String>>()
        val filter = MemberLinkFilter(null, state, { _ -> types }) { c, m -> resolved += c to m }
        val head = "com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl#triggerInnerCardGroup2First"
        val headLinked = filter.apply(head)
            ?.items
            ?.map { head.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals("the head row must be highlighted", listOf(head), headLinked)
        val cont = "Deduct"
        val contLinked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals("the continuation row must be highlighted too", listOf(cont), contLinked)
        // The continuation resolves the stitched, complete method name.
        assertEquals(
            "com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl" to "triggerInnerCardGroup2FirstDeduct",
            resolved.lastOrNull()
        )
        // The head link looks its member up at click time, so it lands on the method — not the class fallback.
        assertEquals("triggerInnerCardGroup2FirstDeduct", state.completedMembers[head])
    }

    @Test
    fun testMemberWrapSplitBeforeHashResolvesToFullMethod() {
        // The wrap split right before the `#`: the head is the whole class (which TypeLinkFilter highlights on
        // its own row) and `#method` is on the next row — both rows are highlighted and both reach the method.
        val state = WrapState()
        var captured: Pair<String, String>? = null
        val filter = MemberLinkFilter(null, state, { _ -> types }) { c, m -> captured = c to m }
        val head = "com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl"
        filter.apply(head)
        val cont = "#triggerInnerCardGroup2FirstDeduct"
        val contLinked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals("the continuation row must be highlighted", listOf(cont), contLinked)
        assertEquals(
            "com.cnsharp.order.biz.service.impl.LoanOrderServiceImpl" to "triggerInnerCardGroup2FirstDeduct",
            captured
        )
        // The head (a type link) is completed with the member so it opens the method, not the class.
        assertEquals("triggerInnerCardGroup2FirstDeduct", state.completedMembers[head])
    }

    @Test
    fun testContinuationThatIsANewReferenceIsNotGluedOn() {
        // A next line that starts its OWN reference is a new token, not a wrap remainder. Gluing it would
        // produce a bogus `com.foo.Bar#saveUserService` link on an unrelated word.
        val state = WrapState()
        val filter = MemberLinkFilter(null, state, { _ -> types })
        filter.apply("com.foo.Bar#save")
        val cont = "UserService.find"
        val linked = filter.apply(cont)
            ?.items
            ?.map { cont.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertTrue("a new reference must not be glued onto the previous line's member", linked.isEmpty())
    }
}

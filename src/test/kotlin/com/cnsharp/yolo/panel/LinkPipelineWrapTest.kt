package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * End-to-end check that the real filter pipeline (registered in [com.cnsharp.yolo.panel.YoloToolWindowFactory]
 * as File -> Member -> Type -> Stack, sharing one [WrapState]) reconstructs a hard-wrapped member reference.
 *
 * Mirrors the real wiring so a filter-ordering or [WrapState] hand-off bug surfaces here instead of only in
 * the IDE. Runs offline (null project + injected type snapshot); resolution is never invoked.
 */
class LinkPipelineWrapTest {

    private val types = YoloProjectTypes.Snapshot(simple = setOf("LoanOrderServiceImpl", "Bar"))

    private fun pipelineLinksFor(line1: String, line2: String): List<String> {
        val state = WrapState()
        val file = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val member = MemberLinkFilter(null, state, { _ -> types })
        val type = TypeLinkFilter(null, { _ -> types }, state)
        val stack = StackTraceLinkFilter(null, "/tmp/agent-working-dir", state)
        val filters = listOf(file, member, type, stack)
        // Line 1: run every filter in registration order (populates pending state, drops results).
        for (f in filters) f.apply(line1)
        // Line 2: run every filter in order, collect all produced link spans.
        val items = mutableListOf<String>()
        for (f in filters) {
            f.apply(line2)?.items?.mapTo(items) { line2.substring(it.startOffset, it.endOffset) }
        }
        return items
    }

    @Test
    fun testWrappedMemberReconstructsInFullPipeline() {
        val linked = pipelineLinksFor(
            "com.cnsharp.order.biz.service.impl.LoanOrderServiceIm",
            "pl#antiFraudConfirm"
        )
        // The continuation must link the WHOLE `pl#antiFraudConfirm` (to the method), not just `pl`.
        assertEquals(listOf("pl#antiFraudConfirm"), linked)
    }

    @Test
    fun testWrappedMemberWithIndentation() {
        val linked = pipelineLinksFor(
            "com.cnsharp.order.biz.service.impl.LoanOrderServiceIm",
            "  pl#antiFraudConfirm"
        )
        assertEquals(listOf("pl#antiFraudConfirm"), linked)
    }
}

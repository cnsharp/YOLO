package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the *interaction* between the two terminal link filters that both fire on the
 * same agent output:
 *
 *  - [FileLinkFilter] — full path references (`src/foo/Bar.kt`, `…/dubbo-service.xml:387`, …);
 *  - [StackTraceLinkFilter] — bare file names / stack-trace frames (`Bar.java:123`, `dubbo-service.xml:387`).
 *
 * These run together in the live terminal. When a hyphenated file name is the *tail* of a directory path
 * (e.g. `dubbo-service.xml` inside `…/resources/dubbo-service.xml:387`), a bare-name/frame pattern can
 * match the *tail* of that name (`service.xml:387`) and produce a second, overlapping link. The terminal
 * then renders the reference as "split" at a hyphen. That bug is invisible to a single-filter unit test
 * (each filter, in isolation, links its part correctly — the old `testHyphenatedPathLinksEntirely` passed
 * for exactly this reason). So this class runs BOTH filters on the same text and asserts their linked
 * spans never *partially* overlap: distinct spans may not share a character.
 *
 * Runs offline (no `BasePlatformTestCase`): both filters only capture `project` for the deferred click
 * handler, so `apply()` is fully testable with a `null` project.
 */
class CombinedLinkFiltersTest {

    /** All (start, endExclusive) spans produced by both filters on [text]. */
    private fun linkedSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        for (filter in listOf(
            FileLinkFilter(null, "/tmp/agent-working-dir"),
            StackTraceLinkFilter(null, "/tmp/agent-working-dir"),
        )) {
            filter.apply(text)?.items?.forEach { spans += (it.startOffset to it.endOffset) }
        }
        return spans
    }

    /** Fails if two *distinct* spans share any character (the "split" symptom). Identical spans are fine. */
    private fun assertNoDistinctOverlap(text: String) {
        val spans = linkedSpans(text)
        for (i in spans.indices) {
            for (j in i + 1 until spans.size) {
                val (s1, e1) = spans[i]
                val (s2, e2) = spans[j]
                val intersects = s1 < e2 && s2 < e1
                assertTrue(
                    "two distinct links overlap in: \"$text\" | spans=$spans",
                    !(intersects && spans[i] != spans[j])
                )
            }
        }
    }

    @Test
    fun hyphenatedPathWithLineDoesNotSplit() {
        assertNoDistinctOverlap("app/biz/service-impl/src/main/resources/dubbo-service.xml:387")
    }

    @Test
    fun hyphenatedPathWithoutLineDoesNotSplit() {
        assertNoDistinctOverlap(".venus/venus_unused_app_order-batch-timing_uat_20260820_144426.tsv")
    }

    @Test
    fun hyphenatedPathsInsideSentenceDoNotSplit() {
        assertNoDistinctOverlap("see .venus/venus_unused_app_order-batch-timing_uat_20260820_144426.tsv here")
        assertNoDistinctOverlap("error at app/biz/service-impl/src/main/resources/dubbo-service.xml:387 now")
    }

    @Test
    fun fullPathStillLinkedAsSingleSpan() {
        // The whole reference must remain one link (FileLinkFilter wins, no competing bare-name link).
        val text = ".venus/venus_unused_app_order-batch-timing_uat_20260820_144426.tsv"
        val spans = linkedSpans(text)
        assertEquals("expected exactly one link spanning the whole path", 1, spans.size)
        assertEquals("link must cover the entire path", 0 to text.length, spans[0])
    }

    @Test
    fun genuineStackFrameStillLinksAndDoesNotSplit() {
        // A real frame's file name is preceded by `(` (not `-`), so it must still link — and must not
        // collide with anything.
        val text = "at com.foo.Bar.run(dubbo-service.xml:387)"
        val texts = linkedSpans(text).map { text.substring(it.first, it.second) }
        assertTrue("bare stack frame should still be linked", texts.contains("dubbo-service.xml:387"))
        assertNoDistinctOverlap(text)
    }
}

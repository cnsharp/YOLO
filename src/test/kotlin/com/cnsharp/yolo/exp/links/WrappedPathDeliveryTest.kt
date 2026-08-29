package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.execution.impl.FilterApplyResult
import com.intellij.execution.impl.HypertextInput
import com.intellij.execution.impl.applyToLineRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the filters through the **real** line-delivery path the reworked terminal uses, rather than
 * calling `applyFilter` directly.
 *
 * The terminal invokes filters via `HyperlinksKt.applyToLineRange(filter, hypertextInput, startLine,
 * endLine, …)`, which pulls each line out of a `HypertextInput` and calls `applyToLine(filter, lineText,
 * lineStartOffset)`. That layer is where several regressions lived (how lines are handed over, which
 * `entireLength` a filter sees), and a test that calls `applyFilter` straight on the filter cannot see any
 * of it. Hence this test: a fake [HypertextInput] plus the platform's own entry point.
 *
 * Runs offline — no application, no project, no terminal widget.
 */
class WrappedPathDeliveryTest {

    /** Stand-in for the terminal's document over the given [lines] (each terminated by one `\n`). */
    private fun inputOf(lines: List<String>): HypertextInput {
        val starts = ArrayList<Int>()
        var offset = 0
        for (line in lines) {
            starts += offset
            offset += line.length + 1
        }
        return object : HypertextInput {
            // Kotlin sees the no-arg `getLineCount()` as a property; the others take a line index.
            override val lineCount: Int get() = lines.size
            override fun getLineStartOffset(lineIndex: Int): Int = starts[lineIndex]
            override fun getLineText(lineIndex: Int): String = lines[lineIndex]
        }
    }

    /** Runs [filter] over [lines] through the platform's entry point, collecting per-line link spans. */
    private fun spans(filter: Filter, lines: List<String>): Map<Int, List<Pair<Int, Int>>> {
        val collected = LinkedHashMap<Int, List<Pair<Int, Int>>>()
        // applyToLineRange is an extension on Filter — this is the platform's own entry point.
        filter.applyToLineRange(inputOf(lines), 0, lines.size - 1) { applied: FilterApplyResult ->
            collected[applied.lineNumber] =
                applied.filterResult?.resultItems.orEmpty().map { it.highlightStartOffset to it.highlightEndOffset }
        }
        return collected
    }

    @Test
    fun wrappedPathIsLinkedWhenDeliveredAsTwoRows() {
        // The head ends with a separator (PATH_PATTERN leaves that `/` behind its match), and the tail
        // deliberately carries NO slash: `PageResult.java:31` is not a path on its own, so the standard
        // pass cannot match it. Only a successful reconstruction can produce this link — a slash-bearing
        // tail such as `cnsharp/.../PageResult.java:31` is a legal relative path and links regardless, which
        // made an earlier version of this test pass even with the reconstruction broken.
        val head = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/cnsharp/message/keeper/core/model/"
        val tail = "    PageResult.java:31"
        val lines = listOf(head, tail)

        val spans = spans(FileLinkFilter(null, "/tmp/agent-working-dir", PathWrapState()), lines)

        // The head row is only a fragment, so nothing links there.
        assertEquals("head row must not link", emptyList<Pair<Int, Int>>(), spans[0])
        // The tail must be linked as the continuation of the reconstructed path.
        val tailSpans = spans[1].orEmpty()
        assertEquals("tail row must link exactly once", 1, tailSpans.size)
        assertEquals(
            "link must cover the whole visible tail",
            tail.trimStart(),
            tail.substring(tailSpans[0].first, tailSpans[0].second),
        )
    }

    @Test
    fun wrappedInsideAHyphenatedSegmentIsLinkedWhole() {
        // Reported case: the wrap splits inside `mq-keeper-core`, so the head row ends with a `-`
        // (which is NOT a path separator) and the tail continues with `core/src/...`.
        val head = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-"
        val tail = "  core/src/main/java/com/cnsharp/message/keeper/core/model/PageResult.java:31"

        val spans = spans(FileLinkFilter(null, "/tmp/agent-working-dir", PathWrapState()), listOf(head, tail))

        val tailSpans = spans[1].orEmpty()
        assertEquals("tail row must link exactly once", 1, tailSpans.size)
        assertEquals(
            "link must cover the whole tail, not stop part-way",
            tail.trimStart(),
            tail.substring(tailSpans[0].first, tailSpans[0].second),
        )
    }

    @Test
    fun unwrappedPathIsLinkedWhenDeliveredAsOneRow() {
        val path = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/cnsharp/message/keeper/core/model/PageResult.java:31"

        val spans = spans(FileLinkFilter(null, "/tmp/agent-working-dir", PathWrapState()), listOf(path))

        assertEquals(1, spans[0]?.size)
        assertEquals(path, path.substring(spans[0]!![0].first, spans[0]!![0].second))
    }

    @Test
    fun everyRowIsOfferedToTheFilterInAscendingOrder() {
        // Guards the delivery contract the wrap reconstruction depends on: rows must arrive in order,
        // otherwise the head is seen after the tail and no prefix is ever available.
        val lines = listOf(
            "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/cnsharp/message/keeper/core/mo",
            "del/PageResult.java:31",
        )
        val seen = ArrayList<Int>()
        val counting = object : Filter {
            override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
                seen += line.length
                return null
            }
        }

        spans(counting, lines)

        assertEquals("both rows must be offered, in order", listOf(lines[0].length, lines[1].length), seen)
    }
}

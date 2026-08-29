package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * The single [Filter] this plugin contributes to the terminal, running all five link detectors and
 * merging their results into one reply.
 *
 * Why one filter instead of five: the terminal wraps contributed filters in a `CompositeFilter`, which
 * stops asking further filters as soon as one returns a result carrying `NextAction.EXIT` — and
 * `Filter.Result` defaults to that action. The obvious fix, returning `CONTINUE_FILTERING`, makes the
 * terminal drop the result entirely (no link is rendered at all), so it is not an option. Iterating the
 * detectors here sidesteps that scheduler completely: we decide the order and merge the spans ourselves,
 * and hand the terminal one final result.
 *
 * Only [TypeLinkFilter] and [MemberLinkFilter] are registered. This is deliberate, and it is the result
 * of measurement rather than preference:
 *
 *  - **File paths, stack frames and URLs are already linked by the terminal itself.** The terminal ships
 *    `TerminalGenericFileFilterProvider` on the very same extension point, so those three add nothing we
 *    do not already get — and registering them produced duplicate, partially-rendered links.
 *  - **Ours do not actually render.** Runtime logs confirm [FileLinkFilter] emits correct spans (including
 *    for hard-wrapped paths, verified across several wrap positions and indentations), yet nothing appears
 *    on screen. Clamping those spans to 12 characters changed nothing, so it is not a length limit: the
 *    terminal simply does not render file-path results contributed through this path. Type names, by
 *    contrast, render reliably — which is why they are what we ship.
 *
 * The file/stack/URL detectors are kept (with their tests) because they are correct and may become usable
 * if the rendering path changes; they are just not wired in. To re-enable them, add them to [detectors]
 * below — [FileLinkFilter] must precede [StackTraceLinkFilter], because the former records in
 * [PathWrapState] the span it reconstructed for a hard-wrapped path and the latter suppresses matches
 * overlapping it.
 */
internal class ExpLinkFilter(
    private val project: Project,
) : Filter, DumbAware {

    // Type and member references only — see the class KDoc for why the file/stack/URL detectors are
    // built but not wired in.
    private val detectors: List<Filter> = listOf(
        TypeLinkFilter(project),
        MemberLinkFilter(project),
    )

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val merged = ArrayList<Filter.ResultItem>()
        for (detector in detectors) {
            val result = detector.applyFilter(line, entireLength) ?: continue
            for (item in result.resultItems) {
                // Later detectors must not paint over a span an earlier one already claimed: a type name
                // and a `Class.member` reference can overlap, and the terminal would render the overlap
                // as a single broken link.
                if (merged.none { overlaps(it, item) }) merged += item
            }
        }
        return if (merged.isEmpty()) null else Filter.Result(merged)
    }

    private fun overlaps(a: Filter.ResultItem, b: Filter.ResultItem): Boolean =
        a.highlightStartOffset < b.highlightEndOffset && b.highlightStartOffset < a.highlightEndOffset
}

package com.cnsharp.yolo.panel

/**
 * Shared mutable state for tracking hard-wrapped terminal paths across consecutive physical lines.
 *
 * JediTerm calls each [com.jediterm.terminal.model.hyperlinks.HyperlinkFilter] in registration order
 * for every physical line. One [PathWrapState] is created per terminal session and handed to both
 * [FileLinkFilter] (registered first) and [StackTraceLinkFilter] (registered second), so:
 *
 * - [FileLinkFilter] reads the OLD [pendingPrefix] (captured on the *previous* line), attempts
 *   path reconstruction, and writes [continuationSpan] before returning.
 * - [StackTraceLinkFilter] reads [continuationSpan] and suppresses any bare-name match that already
 *   has a correct link created by [FileLinkFilter].
 *
 * [pendingPrefix] is also written by [FileLinkFilter] at the END of each call: when a path fragment
 * with no extension or line-number appears at the very end of the line, it is stored here so the
 * next call can attempt reconstruction.
 */
class PathWrapState {
    /** Path fragment at the end of the PREVIOUS line that had no extension or line-number. */
    var pendingPrefix: String = ""

    /**
     * When [FileLinkFilter] reconstructed a wrapped path on the CURRENT line, this is the
     * `[startOffset, endOffset)` range in the current line text where the continuation tail was
     * linked. [StackTraceLinkFilter] suppresses any match that overlaps this span to avoid a
     * duplicate (and incorrect) link for the same text.
     */
    var continuationSpan: IntRange? = null
}

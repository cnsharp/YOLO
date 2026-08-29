package com.cnsharp.yolo.exp.links

/**
 * Shared mutable state that lets [FileLinkFilter] and [StackTraceLinkFilter] reconstruct a file path the
 * terminal split across physical lines, and stop the two filters from linking the same fragment twice.
 *
 * One instance is created per filter session and handed to both filters; they must be run in the order
 * File → Stack, because the stack filter reads [continuationSpan] that the file filter writes.
 */
class PathWrapState {
    /** Path fragment at the end of the PREVIOUS line that had no extension or line-number. */
    var pendingPrefix: String = ""

    /**
     * When [FileLinkFilter] reconstructed a wrapped path on the CURRENT line, this is the
     * `[startOffset, endOffset)` range in the current line text where the continuation tail was
     * linked. [StackTraceLinkFilter] suppresses any match that overlaps this span, so the tail is not
     * linked a second time as a bare stack-trace frame.
     */
    var continuationSpan: IntRange? = null
}

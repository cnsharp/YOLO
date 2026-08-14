package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [StackTraceLinkFilter.apply] — the pure link-matching logic — against the reported
 * false-link regression: a path the agent abbreviated with `…` in the *middle* (e.g.
 * `Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)`) must NOT have its tail fragment
 * (`entExtenderConfigurable.kt`) linked as a bare file. The click handler (resolution) is intentionally
 * NOT invoked, so these tests never touch the filesystem or PSI indices — they only assert *what* got
 * linked.
 *
 * Runs without the IntelliJ `BasePlatformTestCase` fixture: [StackTraceLinkFilter] only captures
 * `project` for the deferred click handler, so `apply()` is fully testable with a `null` project (the
 * link's `Runnable` is constructed but never executed here). This keeps `:test` offline — it reuses the
 * local IDEA platform classpath used for compilation instead of the plugin's `testFramework(...)` helper.
 */
class StackTraceLinkFilterTest {

    private fun linked(text: String): List<String> {
        val filter = StackTraceLinkFilter(null, "/tmp/agent-working-dir")
        return filter.apply(text)
            ?.items
            ?.map { text.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testTruncatedMiddleEllipsisTailIsNotLinked() {
        // The agent abbreviated the path with `…` in the middle; the tail is not a real file.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleAsciiDotTailIsNotLinked() {
        // Same, with a three-dot ASCII run instead of `…`.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar...entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleTailWithLineIsNotLinked() {
        // Even when the truncated tail carries a `:line`, it must not link — it is still a fragment.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt:42)").isEmpty())
    }

    @Test
    fun testRealBareFileNameStillLinks() {
        // A genuine bare file name (no preceding `…`) must still be linked.
        assertEquals(listOf("plugin.xml"), linked("updated plugin.xml successfully"))
    }

    @Test
    fun testStackTraceFrameStillLinks() {
        // A real stack-trace frame is not a truncation tail and must link.
        assertEquals(listOf("Bar.java:123"), linked("at com.foo.Bar.method(Bar.java:123)"))
    }

    @Test
    fun testBareNamePrecededByNonTruncationCharStillLinks() {
        // Only `…`/`...` suppresses a bare name; an ordinary (non-separator) preceding char must not.
        assertEquals(listOf("Bar.java"), linked("shown in (Bar.java) frame"))
    }
}

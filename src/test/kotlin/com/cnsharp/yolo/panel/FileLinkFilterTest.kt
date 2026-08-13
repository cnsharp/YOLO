package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [FileLinkFilter.apply] — the pure link-matching logic — against the reported false-link
 * regressions: dotted non-files (`pay.amount.mark`), truncated paths (`…`/`...`), CJK prose, and long
 * paths the terminal hard-wraps across lines. The click handler (resolution) is intentionally NOT invoked,
 * so these tests never touch the filesystem or PSI indices — they only assert *what* got linked.
 *
 * Runs without the IntelliJ `BasePlatformTestCase` fixture: [FileLinkFilter] only captures `project` for
 * the deferred click handler, so `apply()` is fully testable with a `null` project (the link's `Runnable`
 * is constructed but never executed here). This keeps `:test` offline — it reuses the local IDEA platform
 * classpath used for compilation instead of the plugin's `testFramework(...)` helper, which cannot parse
 * IDEA 2026.2's module descriptors.
 */
class FileLinkFilterTest {

    private fun linked(text: String): List<String> {
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir")
        return filter.apply(text)
            ?.items
            ?.map { text.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testRelativePathWithExtension() {
        assertEquals(listOf("src/main/Foo.kt"), linked("see src/main/Foo.kt here"))
    }

    @Test
    fun testPathWithLineAndColumn() {
        assertEquals(listOf("src/foo/Bar.kt:42:13"), linked("error at src/foo/Bar.kt:42:13"))
    }

    @Test
    fun testAbsoluteUnixPath() {
        assertEquals(listOf("/abs/Bar.java"), linked("file /abs/Bar.java not found"))
    }

    @Test
    fun testWindowsPath() {
        assertEquals(listOf("C:\\foo\\Bar.kt"), linked("open C:\\foo\\Bar.kt"))
    }

    @Test
    fun testLineReferenceWithoutExtensionStillLinks() {
        // `:line` satisfies the completion requirement even when there is no extension.
        assertEquals(listOf("./Makefile:10"), linked("edit ./Makefile:10"))
    }

    @Test
    fun testDirectoryReferenceWithoutExtensionOrLineIsNotLinked() {
        // `src/main/resources/config` has no extension and no line — it is a directory, not openable, and
        // (more importantly) could be a wrapped-path fragment; it must not be linked.
        assertTrue(linked("under src/main/resources/config now").isEmpty())
    }

    @Test
    fun testDottedNonFileIsNotLinked() {
        // `pay.amount.mark` is not a file — no recognized extension, no line number.
        assertTrue(linked("value pay.amount.mark changed").isEmpty())
    }

    @Test
    fun testTruncatedEllipsisPathIsNotLinked() {
        assertTrue(linked("open /Users/me/Proj…name please").isEmpty())
    }

    @Test
    fun testTruncatedAsciiDotPathIsNotLinked() {
        assertTrue(linked("open /Users/me/Proj...name please").isEmpty())
    }

    @Test
    fun testCjkSentenceLinksOnlyThePath() {
        assertEquals(listOf("src/main/Foo.kt"), linked("扫描src/main/Foo.kt失效的key"))
    }

    @Test
    fun testLongPathLinksOnceWhenNotWrapped() {
        val path = "./app/biz/service-impl/target/order-biz-service-impl-5.584.3-SNAPSHOT/WEB-INF/classes/config/business.properties"
        assertEquals(listOf(path), linked(path))
    }

    @Test
    fun testWrappedHeadFragmentIsNotLinked() {
        // Simulate the terminal hard-wrap: the head fragment has neither an extension nor a line number.
        val head = "./app/biz/service-impl/target/order-biz-service-impl-5.584.3-SNAPSHOT/WEB-INF/cla"
        assertTrue(linked(head).isEmpty())
    }

    @Test
    fun testWrappedTailFragmentStillLooksLikeAFile() {
        // The tail fragment ends in a recognized extension, so it is still recognized as a (single) file.
        // This is the unavoidable best case for a hard-wrapped path: the head is dropped, at most one
        // complete-looking fragment links.
        assertEquals(listOf("sses/config/business.properties"), linked("sses/config/business.properties"))
    }

    @Test
    fun testQuotedPathWithSpaces() {
        assertEquals(listOf("/path with space/Bar.kt"), linked("""open "/path with space/Bar.kt" now"""))
    }
}

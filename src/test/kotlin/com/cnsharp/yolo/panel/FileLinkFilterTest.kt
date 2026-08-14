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
    fun testTruncatedMiddleEllipsisTailIsNotLinked() {
        // A path abbreviated with `…` in the middle: the fragment after the marker is the tail of a
        // truncated path, not a real file, so neither the head (`com/cnshar`) nor the tail
        // (`entExtenderConfigurable.kt`) may link.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleEllipsisWithFurtherPathIsNotLinked() {
        // The tail can itself be a longer path (slash right after `…`); it must still not link.
        assertTrue(linked("open src/main/kotlin/com/cnshar…/real/AgentExtenderConfigurable.kt end").isEmpty())
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

    @Test
    fun testExtensionIsNotTruncatedToPrefix() {
        // A listed extension must match as a *full* extension, never as a prefix of a longer one.
        // Regression: the regex grabbed the first listed extension it could — `.markdown` linked only
        // `Foo.m`, `.kts` linked only `Foo.kt`, `.json5` only `Foo.json`. These all have a longer
        // recognized extension, so the whole name must link.
        assertEquals(listOf("src/main/Foo.markdown"), linked("cat src/main/Foo.markdown please"))
        assertEquals(listOf("src/main/Foo.kts"), linked("see src/main/Foo.kts here"))
        assertEquals(listOf("src/main/Foo.json5"), linked("open src/main/Foo.json5 end"))
        assertEquals(listOf("src/main/Foo.mjs"), linked("view src/main/Foo.mjs end"))
    }

    @Test
    fun testUnrecognizedExtensionIsNotLinked() {
        // Extensions that merely *contain* a listed one as a prefix but are not themselves recognized
        // (.module, .commit, .mlis, .more) must not link — and must not be truncated to a wrong file.
        assertTrue(linked("see src/main/Foo.module here").isEmpty())
        assertTrue(linked("edit src/main/Bar.commit now").isEmpty())
        assertTrue(linked("open src/main/Baz.mlis end").isEmpty())
        assertTrue(linked("view src/main/MyClass.more end").isEmpty())
    }

    @Test
    fun testRealSingleCharExtensionStillLinks() {
        // A genuine `.m` (Objective-C) file must still link — the boundary only rejects *prefix* matches.
        assertEquals(listOf("src/main/Foo.m"), linked("see src/main/Foo.m here"))
    }

    // ---- Supplementary cases: branches not covered above ----

    @Test
    fun testPathWithLineRange() {
        // A `path:start-end` range (e.g. a diff hunk) links the whole reference and opens at the start line.
        assertEquals(listOf("src/main/Foo.kt:12-20"), linked("changed src/main/Foo.kt:12-20"))
    }

    @Test
    fun testQuotedPathWithLineAndColumn() {
        // A quoted path with embedded spaces may carry `:line:column` outside the closing quote. The path
        // and the `:line:col` are two separate (quote-excluding) links; the closing quote is never clickable.
        assertEquals(
            listOf("/path with space/Bar.kt", ":42:13"),
            linked("""open "/path with space/Bar.kt":42:13 now"""),
        )
    }

    @Test
    fun testQuotedPathTruncatedNotLinked() {
        // A quoted path the agent abbreviated with `…` mid-way is incomplete and must not link (the
        // `raw.contains('…')` guard in the quoted loop).
        assertTrue(linked("""open "/Users/me/Proj…name/Bar.kt" end""").isEmpty())
    }

    @Test
    fun testWindowsPathWithLineAndColumn() {
        assertEquals(listOf("""C:\foo\Bar.kt:42:13"""), linked("""error at C:\foo\Bar.kt:42:13"""))
    }

    @Test
    fun testHomePathWithLine() {
        // A `~`-prefixed (home-relative) path with a line reference must link.
        assertEquals(listOf("~/foo/Bar.kt:3"), linked("edit ~/foo/Bar.kt:3 please"))
    }

    @Test
    fun testMultiplePathsInOneLine() {
        // Two independent references on one line each become their own link.
        assertEquals(listOf("src/a/Foo.kt", "src/b/Bar.java"), linked("edit src/a/Foo.kt and src/b/Bar.java"))
    }
}

package com.cnsharp.yolo.exp.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [FileLinkFilter.applyFilter] — the pure link-matching logic — against the reported false-link
 * regressions: dotted non-files (`pay.amount.mark`), truncated paths (`…`/`...`), CJK prose, and long
 * paths the terminal hard-wraps across lines. The click handler (resolution) is intentionally NOT invoked,
 * so these tests never touch the filesystem or PSI indices — they only assert *what* got linked.
 *
 * Runs without the IntelliJ `BasePlatformTestCase` fixture: [FileLinkFilter] only captures `project` for
 * the deferred click handler, so filtering is fully testable with a `null` project (the link's
 * `HyperlinkInfo` is constructed but never executed here). This keeps `:test` offline — it reuses the
 * local IDEA platform classpath used for compilation instead of the plugin's `testFramework(...)` helper,
 * which cannot parse IDEA 2026.2's module descriptors.
 */
class FileLinkFilterTest {

    private fun linked(text: String): List<String> {
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir")
        return filter.applyFilter(text, text.length)
            ?.resultItems
            ?.map { text.substring(it.highlightStartOffset, it.highlightEndOffset) }
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
    fun testHyphenatedPathLinksEntirely() {
        // Paths containing hyphens (e.g. report filenames like `ParamConfigService-unused-fields.html`)
        // must not be split at the hyphen. The regex character class `[A-Za-z0-9._\-]` includes `-`,
        // but we keep a regression test so any future change that drops it is caught.
        val path = "docs/ParamConfigService-unused-fields.html"
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

    // ---- Regression: the resolved path must not double-append the extension ----
    // A [PATH_PATTERN] match's group 1 already contains the extension, so the target is just `raw`.
    // Historically the code re-appended `.${ext}`, producing `src/main/Foo.kt.kt`, which `resolve` could
    // never find — the link showed up but clicking navigated nowhere ("不能准确定位到", e.g. a
    // `.venus/…tsv` reference that never opened). These assertions fail if the re-append is reintroduced.
    @Test
    fun testResolvedPathHasNoDoubleExtension() {
        assertEquals("src/main/Foo.kt", fileLinkTarget("src/main/Foo.kt", "kt"))
        assertEquals(".venus/venus_marked_delete_order_test1_20260819_155241.tsv",
            fileLinkTarget(".venus/venus_marked_delete_order_test1_20260819_155241.tsv", "tsv"))
        assertEquals("/abs/Bar.java", fileLinkTarget("/abs/Bar.java", "java"))
        // No-extension match (carried by `:line`): target is the raw path as-is.
        assertEquals("./Makefile", fileLinkTarget("./Makefile", null))
    }

    @Test
    fun testUnquotedPathResolvesToSingleExtension() {
        // End-to-end detection check: a relative `.tsv` reference links as the exact single-extension path
        // (no doubled `.tsv.tsv`), so `resolve` is given a path that actually exists.
        val ref = ".venus/venus_marked_delete_order_test1_20260819_155241.tsv"
        assertEquals(listOf(ref), linked("see $ref here"))
    }

    // ---- Hard-wrap path reconstruction ----

    private fun linkedWithState(line1: String, line2: String): List<String> {
        val state = PathWrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        filter.applyFilter(line1, line1.length)       // processes head line, sets pendingPrefix
        return filter.applyFilter(line2, line2.length)
            ?.resultItems
            ?.map { line2.substring(it.highlightStartOffset, it.highlightEndOffset) }
            .orEmpty()
    }

    @Test
    fun testHardWrapHeadEndingWithSeparatorSetsPendingPrefix() {
        // Directly probes head detection (unlike the linked-span assertions, which a standalone relative
        // path on the tail line can satisfy without any reconstruction happening at all).
        val head = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/"
        val state = PathWrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.applyFilter(head, head.length)
        assertEquals("trailing separator must not break head detection", head, state.pendingPrefix)
    }

    @Test
    fun testRealWorldWrapEndingAtPathSeparator() {
        // The wrap lands right after a `/`, so the head line ends with a separator — PATH_PATTERN cannot
        // include that trailing `/` in its match, which would previously defeat head detection.
        //
        // The tail deliberately carries NO slash: `PageResult.java:31` is not a valid path on its own, so
        // the standard pass cannot match it. Only a successful reconstruction can produce this link —
        // otherwise the test would pass spuriously (a slash-bearing tail like `cnsharp/.../PageResult.java:31`
        // is a legal relative path and gets linked with or without reconstruction).
        val head = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/cnsharp/message/keeper/core/model/"
        val tail = "    PageResult.java:31"
        assertEquals(listOf("PageResult.java:31"), linkedWithState(head, tail))
    }

    @Test
    fun testHardWrapHeadSetsPendingPrefix() {
        val state = PathWrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.applyFilter(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde".length,
        )
        assertEquals(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            state.pendingPrefix
        )
    }

    @Test
    fun testHardWrapPathReconstructedOnContinuationLine() {
        // The reported bug: `…/statemachine/Orde` wraps; the continuation `rTransitionContext.java`
        // must be linked as the tail of the full reconstructed path.
        val tail = "        rTransitionContext.java"
        val linked = linkedWithState(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            tail
        )
        assertEquals(listOf("rTransitionContext.java"), linked)
    }

    @Test
    fun testRealWorldHardWrappedAbsolutePathWithLine() {
        // Reported case: an absolute path the terminal hard-wrapped mid-directory name (`src/ma` |
        // `in/java/...`), with the continuation line indented by two spaces. The tail must be linked
        // as the continuation of the FULL reconstructed path (and carry its `:33`).
        val head = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/ma"
        val tail = "  in/java/com/cnsharp/message/keeper/core/service/MessageKeepServiceImpl.java:33"
        assertEquals(
            listOf("in/java/com/cnsharp/message/keeper/core/service/MessageKeepServiceImpl.java:33"),
            linkedWithState(head, tail),
        )
    }

    @Test
    fun testRealWorldPathUnwrappedInOneLine() {
        // The same path, delivered as a single (unwrapped) line. If this fails, the problem has nothing
        // to do with wrapping — the path itself is not being matched.
        val path = "/Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java/com/cnsharp/message/keeper/core/service/MessageKeepServiceImpl.java:33"
        assertEquals(listOf(path), linked(path))
    }

    @Test
    fun testRealWorldMessageKeepServiceImplAsPrinted() {
        // Exactly as reported: the head line also carries a qualified type name and an em-dash before the
        // path. The path head is `.../mq-keeper-core`; the tail continues at `/src/main/java/...`.
        val head = " com.cnsharp.message.keeper.core.service.MessageKeepServiceImpl — /Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core"
        val tail = "  /src/main/java/com/cnsharp/message/keeper/core/service/MessageKeepServiceImpl.java:33"
        assertEquals(
            listOf("/src/main/java/com/cnsharp/message/keeper/core/service/MessageKeepServiceImpl.java:33"),
            linkedWithState(head, tail),
        )
    }

    @Test
    fun testRealWorldPageResultAsPrinted() {
        // Second reported case: the wrap splits inside `.../src/main/java`, and `java` is a listed
        // extension — so the head must still be treated as a wrap head (no dot in the match's own
        // extension position), not as a complete file.
        val head = "- com.cnsharp.message.keeper.core.model.PageResult — /Users/qinqinbo/IdeaProjects/mq-keeper/mq-keeper-core/src/main/java"
        val tail = "  /com/cnsharp/message/keeper/core/model/PageResult.java:31"
        assertEquals(
            listOf("/com/cnsharp/message/keeper/core/model/PageResult.java:31"),
            linkedWithState(head, tail),
        )
    }

    @Test
    fun testHardWrapPathWithLineReconstructed() {
        // Same, but the continuation line also carries a `:line` suffix.
        val tail = "rTransitionContext.java:42"
        val linked = linkedWithState(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            tail
        )
        assertEquals(listOf("rTransitionContext.java:42"), linked)
    }

    @Test
    fun testExtensionMidWrapDoesNotProducePhantomLink() {
        // When the terminal wraps INSIDE the extension (e.g. "build.gradl" | "e"), the pending prefix
        // must NOT be stored — its last segment contains a dot, indicating a partial extension — so
        // the continuation line's single letter is never linked.
        val state = PathWrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.applyFilter("some/path/build.gradl", "some/path/build.gradl".length)
        assertEquals("extension-split head must not set pendingPrefix", "", state.pendingPrefix)
        // The continuation line must produce no link.
        val result = filter.applyFilter("e", 1)
        assertTrue(result == null || result.resultItems.isEmpty())
    }

    @Test
    fun testBlankLineBreaksWrapSequence() {
        // An empty line between head and continuation clears the pending prefix.
        val state = PathWrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.applyFilter("src/main/java/com/example/OrderTrans", "src/main/java/com/example/OrderTrans".length)
        filter.applyFilter("", 0)            // blank line — must clear pendingPrefix
        assertEquals("", state.pendingPrefix)
        // Continuation line is now treated standalone, not as a reconstruction target.
        val result = filter.applyFilter("actionContext.java", "actionContext.java".length)
        // Without a prefix the standalone "actionContext.java" has no directory component, so
        // FileLinkFilter (PATH_PATTERN) produces nothing — StackTraceLinkFilter would link it.
        assertTrue(result == null || result.resultItems.isEmpty())
    }
}

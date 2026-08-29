package com.cnsharp.yolo.exp.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [TypeLinkFilter.applyFilter] — the pure link-matching / gating logic — covering project-type
 * links and the bare source-file-name fallback added for Kotlin file facades (e.g. `YoloNavigation`, a
 * file of top-level functions rather than a class).
 *
 * The click handler (resolution) is intentionally NOT invoked, so these tests never touch the filesystem
 * or PSI indices — they only assert *what* got linked. A fixed [YoloProjectTypes.Snapshot] is injected via
 * [TypeLinkFilter.typesProvider] so the gating decision is deterministic and the test needs no live
 * project (keeping `:test` offline, like [FileLinkFilterTest] / [StackTraceLinkFilterTest]).
 */
class TypeLinkFilterTest {

    private val types = YoloProjectTypes.Snapshot(
        simple = setOf("ExpLinkFilterProvider", "AgentExtenderSettingsExp", "TypeLinkFilter"),
        files = setOf("YoloNavigation", "Main"),
    )

    private fun linked(text: String): List<String> {
        val filter = TypeLinkFilter(null) { _ -> types }
        return filter.applyFilter(text, text.length)
            ?.resultItems
            ?.map { text.substring(it.highlightStartOffset, it.highlightEndOffset) }
            .orEmpty()
    }

    @Test
    fun testFileFacadeIsLinked() {
        // `YoloNavigation` is a Kotlin file of top-level functions (no class): the file-name fallback must
        // link it even though it is absent from the class sets.
        assertEquals(listOf("YoloNavigation"), linked("open YoloNavigation now"))
    }

    @Test
    fun testProjectClassStillLinked() {
        // A project class present in the simple-name set must still be linked (regression guard for the
        // primary type-link path).
        assertEquals(listOf("ExpLinkFilterProvider"), linked("see ExpLinkFilterProvider here"))
    }

    @Test
    fun testQualifiedProjectTypeLinked() {
        assertEquals(
            listOf("com.cnsharp.yolo.exp.links.TypeLinkFilter"),
            linked("defined in com.cnsharp.yolo.exp.links.TypeLinkFilter"),
        )
    }

    @Test
    fun testUnknownCapitalizedWordNotLinked() {
        // Ordinary capitalized words that are not project types must stay plain text (noise gating).
        assertTrue(linked("class FooBar created").isEmpty())
    }

    @Test
    fun testJdkTypeNotLinked() {
        // Ubiquitous JDK types are excluded so they are not painted blue everywhere.
        assertTrue(linked("use String and List here").isEmpty())
    }

    @Test
    fun testExternalQualifiedTypeNotLinked() {
        // Library / platform types are excluded by design, even when fully qualified.
        assertTrue(linked("implements com.intellij.execution.filters.ConsoleFilterProviderEx").isEmpty())
    }

    @Test
    fun testTrailingExtensionNotLinkedByTypeFilter() {
        // `YoloNavigation.kt` carries a recognized extension, so it is a *file* link (FileLinkFilter's job),
        // not a type link — TypeLinkFilter must not also link the bare `YoloNavigation` portion, which would
        // collide with the file link.
        assertTrue(linked("edit YoloNavigation.kt please").isEmpty())
    }
}

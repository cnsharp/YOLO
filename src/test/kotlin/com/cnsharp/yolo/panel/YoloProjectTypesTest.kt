package com.cnsharp.yolo.panel

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [YoloProjectTypes.scopeProjectSimpleNames] — the gate that decides which type simple
 * names are admitted to the terminal link cache. It must contain ONLY names that truly live in the project's
 * content roots; library / JDK types and ordinary capitalized words that merely coincide with a class name
 * somewhere (e.g. `Now`, `Wait`, `Result`) must be excluded.
 *
 * These run against a fake [ChooseByNameContributor] (no live project / indices), keeping `:test` fully
 * offline — the same approach as the other filter tests. The fake models Java's `AllClassesContributor`,
 * whose `getNames` is scope-blind (returns project + library + JDK names) while `getItemsByName(..., false)`
 * respects project scope.
 */
class YoloProjectTypesTest {

    /** Trivial NavigationItem so the fake contributor can return a non-empty project-scope result. */
    private class FakeItem : NavigationItem {
        override fun getName(): String = "fake"
        override fun getPresentation(): ItemPresentation? = null
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }

    /** Scope-blind contributor: getNames returns everything, getItemsByName only resolves project items. */
    private fun blindContributor(projectNames: Map<String, Boolean>): ChooseByNameContributor {
        return object : ChooseByNameContributor {
            // Params are Project? (not Project) because scopeProjectSimpleNames is exercised here with a
            // null project (offline test seam); the contributor ignores the project entirely. The Java
            // interface declares a platform type, so widening to nullable in the override is allowed.
            override fun getNames(project: Project?, includeNonProjectItems: Boolean): Array<String> =
                projectNames.keys.toTypedArray()

            override fun getItemsByName(
                name: String,
                pattern: String,
                project: Project?,
                includeNonProjectItems: Boolean,
            ): Array<NavigationItem> =
                if (projectNames[name] == true) arrayOf(FakeItem()) else emptyArray()
        }
    }

    /**
     * Core bug regression: `Now`, `Wait`, `Result`, `String`, `List` must NOT be linked as types even though
     * a scope-blind contributor reports them. Only `MyWidget` (which resolves to a project item) survives.
     */
    @Test
    fun testOnlyProjectScopedNamesSurvive() {
        val contributor = blindContributor(
            mapOf(
                "MyWidget" to true,   // real project type
                "Now" to false,       // ordinary capitalized word coinciding with a class name
                "Wait" to false,      // ordinary capitalized word coinciding with a class name
                "Result" to false,    // common capitalized word
                "String" to false,    // ubiquitous JDK type
                "List" to false,      // ubiquitous JDK type
            ),
        )

        val result = scopeProjectSimpleNames(null, listOf(contributor))

        assertEquals(setOf("MyWidget"), result)
    }

    /** When nothing resolves to a project item, the gate must stay empty (no false-positive links). */
    @Test
    fun testEmptyWhenNoProjectItems() {
        val contributor = blindContributor(mapOf("Now" to false, "Wait" to false, "String" to false))

        val result = scopeProjectSimpleNames(null, listOf(contributor))

        assertEquals(emptySet<String>(), result)
    }

    /** Multiple project types across contributors are all admitted. */
    @Test
    fun testMultipleContributorsMerged() {
        val a = blindContributor(mapOf("Foo" to true, "Bar" to false))
        val b = blindContributor(mapOf("Baz" to true, "Qux" to false))

        val result = scopeProjectSimpleNames(null, listOf(a, b))

        assertEquals(setOf("Foo", "Baz"), result)
    }
}

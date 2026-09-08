package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the interaction between [TypeLinkFilter] and [MemberLinkFilter] on a
 * `Class.member` / `Class#member` reference (e.g. `com.foo.Bar#baz`).
 *
 * TypeLinkFilter must NOT link just the class portion (`com.foo.Bar`) of such a reference — doing so makes
 * the terminal render a type link over the prefix and steal the span from MemberLinkFilter, which is the one
 * that navigates to the member. The type pattern therefore refuses to match when immediately followed by a
 * member separator (`#` / `.`) + identifier, leaving the whole reference for MemberLinkFilter.
 *
 * Offline (no `BasePlatformTestCase`): we feed a hand-built [YoloProjectTypes.Snapshot] so the gate passes
 * for `Bar`, and only assert what `apply()` captures (the deferred click handler is never executed).
 */
class TypeLinkFilterMemberRefTest {

    /** Spans TypeLinkFilter would link for [text], with `Bar` known as a project type. */
    private fun typeLinks(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        val types = YoloProjectTypes.Snapshot(setOf("Bar"))
        val filter = TypeLinkFilter(null, { types })
        filter.apply(text)?.items?.forEach { spans += (it.startOffset to it.endOffset) }
        return spans
    }

    @Test
    fun qualifiedClassFollowedByHashMemberIsNotLinkedAsType() {
        // com.foo.Bar#baz must NOT be claimed as the type `com.foo.Bar` — MemberLinkFilter owns it.
        assertEquals(0, typeLinks("com.foo.Bar#baz").size)
    }

    @Test
    fun simpleClassFollowedByHashMemberIsNotLinkedAsType() {
        assertEquals(0, typeLinks("run Bar#baz now").size)
    }

    @Test
    fun qualifiedClassFollowedByDotMemberIsNotLinkedAsType() {
        // The `.` case is already gated by last-segment, but assert it stays excluded for safety.
        assertEquals(0, typeLinks("com.foo.Bar.baz").size)
    }

    @Test
    fun plainQualifiedTypeStillLinked() {
        assertEquals(listOf(4 to 15), typeLinks("see com.foo.Bar here"))
    }

    @Test
    fun plainSimpleTypeStillLinked() {
        assertEquals(listOf(6 to 9), typeLinks("class Bar"))
    }
}

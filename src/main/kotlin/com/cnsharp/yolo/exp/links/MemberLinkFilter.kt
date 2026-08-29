package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * Makes `Class.member` / `Class#member` references clickable, navigating to the specific method, field or
 * inner class — a step finer than [TypeLinkFilter], which lands on the class declaration.
 *
 * The class part must be a known project type (same gate as [TypeLinkFilter], via [YoloProjectTypes]);
 * the member name is not validated while matching. On click we try to pin down the member symbol and fall
 * back to the class declaration when it cannot be resolved — so a bad member reference still navigates
 * somewhere useful instead of dead-clicking.
 *
 * Member resolution is best-effort and language-agnostic (`gotoSymbolContributor` EP); see
 * [resolveMember].
 */
class MemberLinkFilter(private val project: Project) : Filter {

    override fun applyFilter(text: String, entireLength: Int): Filter.Result? {
        if (text.isBlank() || isDiffLine(text) || DumbService.isDumb(project)) return null
        val types = YoloProjectTypes.snapshot(project)
        val items = mutableListOf<Filter.ResultItem>()
        val matcher = MEMBER_REF_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val classRef = matcher.group("class") ?: continue
            val member = matcher.group("member") ?: continue
            val known = if (isQualifiedName(classRef)) lastTypeNameSegment(classRef) in types.simple
                        else types.containsSimple(classRef)
            if (!known) continue
            val link = expLink {
                val classItem = resolveType(project, classRef) ?: return@expLink null
                // Try to land on the member; fall back to the class declaration if it can't be pinned down.
                val memberItem = resolveMember(project, classItem, member)
                val target = memberItem ?: classItem
                { openNavigationItem(target) }
            }
            items.add(Filter.ResultItem(matcher.start(), matcher.end(), link, HIGHLIGHT_ATTRS))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}

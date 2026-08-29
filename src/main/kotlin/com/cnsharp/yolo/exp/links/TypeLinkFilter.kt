package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Makes type references printed by agents clickable — a qualified name (`com.foo.Bar`,
 * `myapp.models.User`, `foo::Bar`, `\App\Models\User`) or a project simple name (`Bar`).
 *
 * Gating is the important part: a candidate is linked only when [YoloProjectTypes] says the project
 * really contains that type, so the regex never paints ordinary capitalized words as links. A qualified
 * reference is accepted when its trailing segment is a known project type; a simple reference when the
 * name itself is. When a bare name is not a type but *is* a project source file — a Kotlin file facade
 * such as [YoloNavigation], which has no enclosing class — the file is linked instead.
 *
 * Actual resolution stays on click ([resolveType]), so streaming output is never blocked by index work.
 * Type resolution is language-agnostic (`gotoClassContributor` EP), so this works in any IDE whose
 * language plugin implements it.
 */
class TypeLinkFilter(
    private val project: Project?,
    private val typesProvider: (Project?) -> YoloProjectTypes.Snapshot = { p ->
        if (p != null) YoloProjectTypes.snapshot(p) else YoloProjectTypes.Snapshot(emptySet(), emptySet())
    },
) : Filter {

    override fun applyFilter(text: String, entireLength: Int): Filter.Result? {
        if (text.isBlank() || isDiffLine(text) || (project != null && DumbService.isDumb(project))) return null
        val types = typesProvider(project)
        val items = mutableListOf<Filter.ResultItem>()
        val matcher = TYPE_NAME_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val qualified = matcher.group("qualified")
            val simple = matcher.group("simple")
            val known = when {
                qualified != null -> lastTypeNameSegment(qualified) in types.simple
                simple != null -> types.containsSimple(simple)
                else -> false
            }
            if (known) {
                val link = expLink {
                    val p = project ?: return@expLink null
                    val name = qualified ?: simple ?: return@expLink null
                    val target = resolveType(p, name) ?: return@expLink null
                    { openNavigationItem(target) }
                }
                items.add(Filter.ResultItem(matcher.start(), matcher.end(), link, HIGHLIGHT_ATTRS))
                continue
            }
            // File-name fallback: a bare source-file base name that is not a class
            // (e.g. a Kotlin file facade like `YoloNavigation`).
            if (simple != null && types.containsFile(simple)) {
                val link = expLink {
                    val p = project ?: return@expLink null
                    val vf = types.resolveFile(simple) ?: return@expLink null
                    { openFileAt(p, File(vf.path), null, null) }
                }
                items.add(Filter.ResultItem(matcher.start(), matcher.end(), link, HIGHLIGHT_ATTRS))
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}

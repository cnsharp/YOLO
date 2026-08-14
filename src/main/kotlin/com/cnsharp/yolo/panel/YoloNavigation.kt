package com.cnsharp.yolo.panel

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import java.io.File

/**
 * Shared, public-API-only navigation helpers reused by the terminal [com.jediterm.terminal.model.hyperlinks.HyperlinkFilter]s.
 * Keeping them in one place avoids duplicating file-open / type-resolution logic across filters.
 *
 * Type resolution is **language-agnostic**: it goes through the platform's `gotoClassContributor`
 * extension point, which every language plugin implements with its own PSI (Java/Kotlin, Python, C# via
 * Rider's ReSharper protocol, Go, Ruby, PHP, Rust, JS/TS, …). So a single code path links types in any
 * supported IDE — no per-language dependency or reflection.
 */

/** Separators a qualified type name may use across languages (Java/C#/Python/Go `.`, Rust/Ruby `::`, PHP `\`). */
internal val QUALIFIED_SEPARATORS = charArrayOf('.', ':', '\\')

/** True when [name] contains any qualified-name separator (i.e. it is a qualified, not simple, name). */
internal fun isQualifiedName(name: String): Boolean = QUALIFIED_SEPARATORS.any { it in name }

/** Normalize a qualified name to a canonical dotted form so names from different languages compare equal. */
internal fun normalizeTypeName(name: String): String = name.replace('\\', '.').replace("::", ".")

/** Last segment of a (possibly qualified) type name, in any language's separator convention. */
internal fun lastTypeNameSegment(name: String): String {
    val idx = name.indexOfLast { it in QUALIFIED_SEPARATORS }
    return if (idx < 0) name else name.substring(idx + 1)
}

/** Open a file in the IDE editor at the given 1-based line/column (converted to 0-based internally). */
internal fun openFileAt(project: Project, file: File, line: Int?, column: Int?) {
    ReadAction.run<Throwable> {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@run
        // OpenFileDescriptor uses 0-based line/column; agent output is 1-based.
        val descriptor = OpenFileDescriptor(project, vFile, (line ?: 1) - 1, (column ?: 1) - 1)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

/**
 * Resolve a (possibly qualified) type name to a navigatable element, searching every language's class
 * contributor. Returns null if no project class matches.
 *
 * Must be called inside a read action. Tolerant of contributors that throw or return empty while their
 * index/backend is still warming up (e.g. Rider's ReSharper-backed C# contributor) — those are simply
 * skipped so the terminal never blocks.
 */
internal fun resolveType(project: Project, name: String): NavigationItem? = ReadAction.compute<NavigationItem?, Throwable> {
    val target = normalizeTypeName(name)
    val lastSeg = lastTypeNameSegment(name)
    val qualifiedQuery = isQualifiedName(name)
    for (contributor in ChooseByNameContributor.CLASS_EP_NAME.extensionList) {
        // Fast pre-filter: skip contributors that don't even know the trailing simple name.
        val knownNames = runCatching { contributor.getNames(project, false) }.getOrNull() ?: continue
        if (lastSeg !in knownNames) continue
        val items = runCatching {
            contributor.getItemsByName(lastSeg, lastSeg, project, false)
        }.getOrNull() ?: continue
        for (item in items) {
            val qn = (contributor as? GotoClassContributor)?.getQualifiedName(item)
            if (qn != null) {
                // Exact or separator-normalized qualified match.
                if (qn == name || normalizeTypeName(qn) == target) return@compute item
                // Simple-name query: match by the item's simple (last) segment.
                if (!qualifiedQuery && lastTypeNameSegment(qn) == name) return@compute item
            } else if (!qualifiedQuery) {
                // Contributor without a qualified name still satisfies a simple-name query.
                return@compute item
            }
        }
    }
    null
}

/**
 * Navigate to a resolved [NavigationItem] (opens its editor / declaration).
 *
 * Must be called on the EDT and **outside** a read action — [NavigationItem.navigate] opens editors. The
 * terminal link wrappers run this inside `invokeLater`, so callers resolve under a read action (e.g.
 * [resolveType]) and then invoke this afterwards.
 */
internal fun openNavigationItem(item: NavigationItem) {
    if (item.canNavigate()) item.navigate(true)
}

/**
 * Best-effort member resolution: given a resolved class element and a member name, find the member symbol
 * (method / field / inner class) via the language-agnostic `gotoSymbolContributor` EP and return it as a
 * navigatable item. We prefer a candidate declared in the same file as the class (so an inherited member
 * from a library / base class is not mistaken for the project one), and return null if none matches — the
 * caller then navigates to the class declaration instead.
 *
 * Must be called inside a read action.
 */
internal fun resolveMember(project: Project, classItem: NavigationItem, member: String): NavigationItem? =
    ReadAction.compute<NavigationItem?, Throwable> {
        val classFile = (classItem as? PsiElement)?.containingFile?.virtualFile ?: return@compute null
        for (contributor in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
            val items = runCatching {
                contributor.getItemsByName(member, member, project, false)
            }.getOrNull() ?: continue
            for (item in items) {
                // Prefer a symbol declared in the same file as the class (a project member, not an inherited one).
                val itemFile = (item as? PsiElement)?.containingFile?.virtualFile
                if (itemFile != null && itemFile == classFile) return@compute item
            }
        }
        null
    }

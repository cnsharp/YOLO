package com.cnsharp.yolo.panel

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazily-built, index-invalidated snapshot of the project's own type simple names plus its source-file
 * base names. The terminal hyperlink filters use it to decide which identifiers are worth turning into
 * clickable links. Qualified-name references are gated cheaply from [Snapshot.simple] (a qualified ref is
 * linked when its trailing segment is a known project type); actual resolution stays lazy, on click.
 *
 * Without this gate, the filters' regexes match every capitalized word — `Result`, `OK`, `Error`, or the
 * `Ctrl` in a `Ctrl/C` shortcut notation — and paint it blue even though it is not a project type. Restricting
 * links to types that really exist in the project's content roots (libraries/JDK excluded) cuts that noise
 * down to genuine project references.
 *
 * Names come from the platform's language-agnostic `gotoClassContributor` extension point, which every
 * language plugin implements with its own PSI (Java/Kotlin, Python, C#/Rider, Go, Ruby, PHP, Rust, …). So
 * the snapshot covers all languages the project mixes, with no per-language dependency.
 *
 * IMPORTANT — [snapshot] must never block. The terminal's hyperlink callbacks run on the JediTerm emulator
 * thread *while it holds the `TerminalTextBuffer` lock*. Doing a read action there deadlocks the EDT (it
 * cannot paint the terminal while that lock is held by a thread waiting on a write action) — which is the
 * freeze this plugin used to cause. So [snapshot] returns the last computed value immediately and
 * (re)computes it on a pooled thread under a read action when the PSI/index has changed; until the refresh
 * finishes, the previous (or empty) snapshot is returned so terminal painting is never stalled.
 */
object YoloProjectTypes {

    /** Source-file extensions we treat as project references for the bare file-name fallback. A curated,
     *  code-oriented subset of [PROGRAMMING_EXT] so a non-source file's base name (e.g. `build`, `README`)
     *  is not accidentally linked. */
    private val SOURCE_FILE_EXT = setOf(
        "kt", "kts", "java", "scala", "sc", "groovy",
        "py", "pyi", "rb", "php", "pl", "pm", "lua",
        "js", "jsx", "mjs", "cjs", "ts", "tsx",
        "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "m", "mm", "swift", "d", "nim",
        "ex", "exs", "clj", "cljs", "erl", "hs", "ml", "fs", "fsx", "jl", "r",
        "proto", "sol", "graphql", "gql", "dart",
    )

    /** Immutable view of the project's type names; cheap to hold and query within a single line scan. */
    data class Snapshot(
        val simple: Set<String>,
        val files: Set<String> = emptySet(),
        /** Base-name → VirtualFile for the bare source-file fallback (project content roots only). */
        val fileMap: Map<String, VirtualFile> = emptyMap(),
    ) {
        fun containsSimple(name: String): Boolean = name in simple
        fun containsFile(name: String): Boolean = name in files
        /** Resolve a bare source-file base name to its VirtualFile, or null if it is not a project file. */
        fun resolveFile(name: String): VirtualFile? = fileMap[name]
    }

    private class Cache {
        @Volatile var snapshot: Snapshot = Snapshot(emptySet())
        @Volatile var modCount: Long = -1L
        val refreshScheduled = AtomicBoolean(false)
    }

    private val caches = ConcurrentHashMap<Project, Cache>()

    init {
        // Drop a project's cache when it closes so the map does not leak closed projects.
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosed(project: Project) {
                    caches.remove(project)
                }
            })
    }

    /**
     * Returns the most recent project-type snapshot, non-blocking. The first call (and any call after the
     * PSI/index changes) schedules a background refresh; until it completes, the previous — or, before the
     * first build, empty — snapshot is returned. Safe to call from the terminal emulator thread.
     */
    fun snapshot(project: Project): Snapshot {
        val cache = caches.computeIfAbsent(project) { Cache() }
        val modCount = PsiModificationTracker.getInstance(project).modificationCount
        if (cache.modCount != modCount && cache.refreshScheduled.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val built = runReadAction { build(project) }
                    cache.snapshot = built
                    cache.modCount = modCount
                } finally {
                    cache.refreshScheduled.set(false)
                }
            }
        }
        return cache.snapshot
    }

    private fun build(project: Project): Snapshot {
        // Project type *simple* names, via the language-agnostic goto-class EP (see scopeProjectSimpleNames
        // for why each name is additionally scope-checked before it is admitted to the gate).
        val simple = scopeProjectSimpleNames(project, ChooseByNameContributor.CLASS_EP_NAME.extensionList.toList())
        // Source-file base names (without extension) so Kotlin file facades — top-level-function files with
        // no enclosing class (e.g. `YoloNavigation.kt`) — are still recognized as project references and can
        // be linked to the file. Restricted to content roots and a code-extension allowlist, matching the
        // class collection above.
        val inProject = ProjectRootManager.getInstance(project).fileIndex
        val files = mutableSetOf<String>()
        val fileMap = mutableMapOf<String, VirtualFile>()
        inProject.iterateContent { vf ->
            if (!vf.isDirectory) {
                val ext = vf.extension
                if (ext != null && ext in SOURCE_FILE_EXT) {
                    val base = vf.nameWithoutExtension
                    if (base != null && base.isNotEmpty()) {
                        files.add(base)
                        fileMap.putIfAbsent(base, vf)
                    }
                }
            }
            true
        }
        return Snapshot(simple, files, fileMap)
    }
}

/**
 * Collects project-content-root type simple names from the given [contributors] for [project].
 *
 * A name is admitted to the set only if it resolves to a *project-scope* (content-root) item via
 * `getItemsByName(name, name, project, false)` — the same project-only check [resolveType][YoloNavigation]
 * uses on click. This second pass is mandatory: `getNames(project, false)` is SCOPE-BLIND for several
 * language contributors (Java's `AllClassesContributor` returns every indexed class short name — project
 * + libraries + JDK — and ignores the `includeNonProjectItems` flag). Feeding `getNames` straight into the
 * gate links ubiquitous types (`String`, `List`, …) and ordinary capitalized words that merely coincide
 * with a class name somewhere (`Now`, `Wait`, `Result`, …) — the "paint every capitalized word blue"
 * behaviour users report. The extra `getItemsByName` pass restores the "content roots only" gate the
 * filters rely on, while the qualified-name gate still works because a qualified project symbol's trailing
 * segment is now in the set.
 *
 * Pure and side-effect free (takes the contributor list explicitly) so it can be unit-tested with a fake
 * [ChooseByNameContributor] — no live project or indices required. [project] is nullable only to permit
 * that offline test seam; production callers always pass a real project.
 */
fun scopeProjectSimpleNames(project: Project?, contributors: List<ChooseByNameContributor>): Set<String> {
    val simple = mutableSetOf<String>()
    for (contributor in contributors) {
        val names = runCatching { contributor.getNames(project, false) }.getOrNull() ?: continue
        for (name in names) {
            val projectItems = runCatching {
                contributor.getItemsByName(name, name, project, false)
            }.getOrNull()
            if (!projectItems.isNullOrEmpty()) simple.add(name)
        }
    }
    return simple
}

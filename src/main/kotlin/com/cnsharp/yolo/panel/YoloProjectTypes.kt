package com.cnsharp.yolo.panel

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazily-built, index-invalidated snapshot of the project's own type names (simple names and
 * fully-qualified names) plus its source-file base names. The terminal hyperlink filters use it to decide
 * which identifiers are worth turning into clickable links.
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
        val qualified: Set<String> = emptySet(),
        val files: Set<String> = emptySet(),
        /** Base-name → VirtualFile for the bare source-file fallback (project content roots only). */
        val fileMap: Map<String, VirtualFile> = emptyMap(),
    ) {
        fun containsSimple(name: String): Boolean = name in simple
        fun containsQualified(name: String): Boolean = name in qualified
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
                    val built = ReadAction.compute<Snapshot, Throwable> { build(project) }
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
        // All languages' project class names, via the language-agnostic goto-class EP.
        // includeNonProjectItems = false restricts to project content (excludes JDK/library types), matching
        // the previous "content roots only" gate so ubiquitous types (String, List, …) are not linked.
        // Both simple and fully-qualified names are collected so the filters can gate precisely — mirroring
        // the old PsiShortNamesCache pass, but driven by the EP every language implements.
        val simple = mutableSetOf<String>()
        val qualified = mutableSetOf<String>()
        for (contributor in ChooseByNameContributor.CLASS_EP_NAME.extensionList) {
            val names = runCatching { contributor.getNames(project, false) }.getOrNull() ?: continue
            simple.addAll(names)
            for (name in names) {
                val items = runCatching {
                    contributor.getItemsByName(name, name, project, false)
                }.getOrNull() ?: continue
                for (item in items) {
                    val navItem = item as? NavigationItem ?: continue
                    val qn = (contributor as? GotoClassContributor)?.getQualifiedName(navItem)
                    if (qn != null) qualified.add(qn)
                }
            }
        }
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
        return Snapshot(simple, qualified, files, fileMap)
    }
}

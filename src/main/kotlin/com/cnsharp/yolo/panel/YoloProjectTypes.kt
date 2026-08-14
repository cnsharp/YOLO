package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazily-built, index-invalidated snapshot of the project's own type names (simple names and
 * fully-qualified names). The terminal hyperlink filters use it to decide which identifiers are worth
 * turning into clickable links.
 *
 * Without this gate, the filters' regexes match every capitalized word — `Result`, `OK`, `Error`, or the
 * `Ctrl` in a `Ctrl/C` shortcut notation — and paint it blue even though it is not a project type. Restricting
 * links to types that really exist in the project's content roots (libraries/JDK excluded) cuts that noise
 * down to genuine project references.
 *
 * IMPORTANT — [snapshot] must never block. The terminal's hyperlink callbacks run on the JediTerm emulator
 * thread *while it holds the `TerminalTextBuffer` lock*. Doing a read action there deadlocks the EDT (it
 * cannot paint the terminal while that lock is held by a thread waiting on a write action) — which is the
 * freeze this plugin used to cause. So [snapshot] returns the last computed value immediately and
 * (re)computes it on a pooled thread under a read action when the PSI/index has changed; until the refresh
 * finishes, the previous (or empty) snapshot is returned so terminal painting is never stalled.
 */
object YoloProjectTypes {

    /** Immutable view of the project's type names; cheap to hold and query within a single line scan. */
    data class Snapshot(
        val simple: Set<String>,
        val qualified: Set<String>,
        val files: Set<String> = emptySet(),
    ) {
        fun containsSimple(name: String): Boolean = name in simple
        fun containsQualified(name: String): Boolean = name in qualified
        fun containsFile(name: String): Boolean = name in files
    }

    private class Cache {
        @Volatile var snapshot: Snapshot = Snapshot(emptySet(), emptySet())
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
        val cache = PsiShortNamesCache.getInstance(project)
        // projectScope also covers libraries; restrict to content roots so ubiquitous JDK/library types
        // (String, List, …) are not treated as project types and re-introduce the highlight noise.
        val scope = GlobalSearchScope.projectScope(project)
        val inProject = ProjectRootManager.getInstance(project).fileIndex
        val simple = mutableSetOf<String>()
        val qualified = mutableSetOf<String>()
        for (name in cache.allClassNames) {
            for (cls in cache.getClassesByName(name, scope)) {
                val vFile = cls.containingFile?.virtualFile ?: continue
                if (!inProject.isInContent(vFile)) continue
                val simpleName = cls.name ?: continue
                simple.add(simpleName)
                cls.qualifiedName?.let { qualified.add(it) }
            }
        }
        // Source-file base names (without extension), so Kotlin file facades — files of top-level functions
        // with no enclosing class (e.g. `YoloNavigation.kt`) — are still recognized as project references and
        // can be linked to the file. Restricted to content roots, matching the class collection above.
        val files = mutableSetOf<String>()
        inProject.iterateContent { vf ->
            if (!vf.isDirectory) {
                val ext = vf.extension
                if (ext == "kt" || ext == "java") vf.nameWithoutExtension?.let { files.add(it) }
            }
            true
        }
        return Snapshot(simple, qualified, files)
    }
}

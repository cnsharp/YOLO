package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.ConsoleFilterProviderEx
import com.intellij.execution.filters.Filter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Contributes this plugin's terminal hyperlinks to IDEA's reworked (block) terminal.
 *
 * The terminal merges every `consoleFilterProvider` — including its own
 * `TerminalGenericFileFilterProvider` — into one `CompositeFilter`, so registering here does not conflict
 * with the built-in file/URL links; it only *adds* the reference kinds the platform does not provide
 * (type names, `Class.member`, stack frames, quoted paths).
 *
 * Both overloads return the same filters, which is what the terminal's own provider does too.
 */
class ExpLinkFilterProvider : ConsoleFilterProviderEx {

    override fun getDefaultFilters(project: Project): Array<Filter> = build(project)

    override fun getDefaultFilters(project: Project, scope: GlobalSearchScope): Array<Filter> = build(project)

    private fun build(project: Project): Array<Filter> = filters.computeIfAbsent(project) { p ->
        // One filter, not several: it runs every detector internally and returns a single merged result.
        arrayOf(ExpLinkFilter(p))
    }

    companion object {
        /**
         * The filters must be stable per project, NOT rebuilt per call.
         *
         * Unlike main's JediTerm widget — which joins physically wrapped rows into one logical line before
         * invoking its filters — the block terminal hands us one document line at a time with no
         * de-wrapping. So `PathWrapState` is load-bearing here (in main it is effectively dead code), and it
         * only works if the same filter instances see consecutive lines. Rebuilding them per call would
         * reset the pending prefix and wrapped paths would never link.
         */
        private val filters = ConcurrentHashMap<Project, Array<Filter>>()

        init {
            // Drop a project's filters when it closes so the map does not leak closed projects.
            ApplicationManager.getApplication().messageBus.connect()
                .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                    override fun projectClosed(project: Project) {
                        filters.remove(project)
                    }
                })
        }
    }
}

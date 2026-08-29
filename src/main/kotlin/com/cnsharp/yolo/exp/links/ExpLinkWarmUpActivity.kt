package com.cnsharp.yolo.exp.links

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Warms the [YoloProjectTypes] snapshot once per project, in the background, during startup.
 *
 * [YoloProjectTypes.snapshot] is non-blocking: calling it kicks off the build on a pooled thread and
 * returns the previous (initially empty) snapshot. Without this warm-up the very first lines the agent
 * prints would be filtered against an empty snapshot, so type links would be missing until the build
 * finished. Running at startup means the snapshot is normally ready before the user launches an agent.
 *
 * Registering this as `backgroundPostStartupActivity` (rather than doing it lazily on first use) is what
 * makes the first line behave like every other line.
 */
class ExpLinkWarmUpActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        runCatching { YoloProjectTypes.snapshot(project) }
    }
}

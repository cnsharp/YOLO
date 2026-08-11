package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.jediterm.terminal.model.hyperlinks.LinkInfo

/**
 * Build a terminal [LinkInfo] whose click runs [navigate] on the EDT (e.g. open a file or PSI
 * declaration) and then auto-hides the YOLO tool window so it no longer obscures the editor.
 *
 * Shared by [FileLinkFilter] and [TypeLinkFilter] to avoid duplicating the navigate-and-hide wiring.
 */
internal fun yoloHyperlink(project: Project, navigate: () -> Unit): LinkInfo =
    LinkInfo(Runnable {
        ApplicationManager.getApplication().invokeLater {
            navigate()
            ToolWindowManager.getInstance(project).getToolWindow(YoloConstants.ID)?.hide()
        }
    })

package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Shared, public-API-only navigation helpers reused by the terminal [com.jediterm.terminal.model.hyperlinks.HyperlinkFilter]s.
 * Keeping them in one place avoids duplicating file-open / PSI-resolution logic across filters.
 */

/** Open a file in the IDE editor at the given 1-based line/column (converted to 0-based internally). */
internal fun openFileAt(project: Project, file: File, line: Int?, column: Int?) {
    ReadAction.run<Throwable> {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@run
        // OpenFileDescriptor uses 0-based line/column; agent output is 1-based.
        val descriptor = OpenFileDescriptor(project, vFile, (line ?: 1) - 1, (column ?: 1) - 1)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

/** Open a PSI element's declaration in the editor at its offset. */
internal fun openElementAt(project: Project, target: PsiElement) {
    ReadAction.run<Throwable> {
        val vFile = target.containingFile?.virtualFile ?: return@run
        val offset = target.textOffset
        val doc = FileDocumentManager.getInstance().getDocument(vFile)
        val (line, col) = if (doc != null) {
            val l = doc.getLineNumber(offset)
            l to (offset - doc.getLineStartOffset(l))
        } else {
            0 to 0
        }
        val descriptor = OpenFileDescriptor(project, vFile, line, col)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

/** Resolve a fully-qualified class name across the whole project (incl. JDK/library sources). */
internal fun resolveQualifiedClass(project: Project, fqn: String): PsiClass? = ReadAction.compute<PsiClass?, Throwable> {
    JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
}

/** Resolve a simple class name, but only to a class inside the project's content roots. */
internal fun resolveSimpleClass(project: Project, name: String): PsiClass? = ReadAction.compute<PsiClass?, Throwable> {
    val cache = PsiShortNamesCache.getInstance(project)
    val classes = cache.getClassesByName(name, GlobalSearchScope.allScope(project))
    val inProject = ProjectRootManager.getInstance(project).fileIndex
    classes.firstOrNull { cls ->
        cls.containingFile?.virtualFile?.let { inProject.isInContent(it) } == true
    }
}

/** Find a member (method / field / inner class) by name within a class (incl. supers); null if absent. */
internal fun findMember(psiClass: PsiClass, name: String): PsiElement? = ReadAction.compute<PsiElement?, Throwable> {
    psiClass.findMethodsByName(name, true).firstOrNull()
        ?: psiClass.findFieldByName(name, true)
        ?: psiClass.findInnerClassByName(name, true)
}

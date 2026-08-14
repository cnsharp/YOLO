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
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
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

/** Resolve a fully-qualified class name across the project and its dependencies (incl. JDK/library sources). */
internal fun resolveQualifiedClass(project: Project, fqn: String): PsiClass? = ReadAction.compute<PsiClass?, Throwable> {
    JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))
}

/**
 * Resolve a simple class name. Project-defined types take precedence; if the project has no such class,
 * fall back to a library/imported type (IDEA will open its source if available, or decompile it).
 */
internal fun resolveSimpleClass(project: Project, name: String): PsiClass? = ReadAction.compute<PsiClass?, Throwable> {
    val cache = PsiShortNamesCache.getInstance(project)
    val classes = cache.getClassesByName(name, GlobalSearchScope.projectScope(project))
    val inProject = ProjectRootManager.getInstance(project).fileIndex
    classes.firstOrNull { cls -> cls.containingFile?.virtualFile?.let { inProject.isInContent(it) } == true }
        ?: classes.firstOrNull()
}

/** Find a member (method / field / inner class) by name within a class (incl. supers); null if absent. */
internal fun findMember(psiClass: PsiClass, name: String): PsiElement? = ReadAction.compute<PsiElement?, Throwable> {
    psiClass.findMethodsByName(name, true).firstOrNull()
        ?: psiClass.findFieldByName(name, true)
        ?: psiClass.findInnerClassByName(name, true)
}

/**
 * Whether [element] is declared inside the project's own content (a source file under a content root),
 * as opposed to a library / JDK class.
 *
 * Member links use this so an *inherited* member declared outside the project — e.g.
 * `CustomerException#getMessage`, inherited from `Throwable` — navigates to the *referenced* class instead
 * of diving into the JDK sources. A member declared in the referenced class itself (or in another project
 * class) still navigates precisely to the member.
 */
internal fun isInProjectContent(element: PsiElement, project: Project): Boolean {
    val declaringClass = when (element) {
        is PsiMethod -> element.containingClass
        is PsiField -> element.containingClass
        is PsiClass -> element.containingClass
        else -> null
    } ?: return false
    val vf = declaringClass.containingFile?.virtualFile ?: return false
    return ProjectRootManager.getInstance(project).fileIndex.isInContent(vf)
}

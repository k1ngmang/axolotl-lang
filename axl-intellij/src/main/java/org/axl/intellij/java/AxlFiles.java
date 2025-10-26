package org.axl.intellij.java;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.List;
import org.axl.intellij.AxlFileType;
import org.axl.intellij.psi.AxlFile;

/** Finds .axl files by the Java class or package they define. */
final class AxlFiles {

    private AxlFiles() {}

    static List<AxlFile> byClassName(Project project, String simpleName, GlobalSearchScope scope) {
        List<AxlFile> result = new ArrayList<>();
        for (VirtualFile file : FilenameIndex.getVirtualFilesByName(simpleName + ".axl", scope)) {
            PsiFile psi = PsiManager.getInstance(project).findFile(file);
            if (psi instanceof AxlFile axl) {
                result.add(axl);
            }
        }
        return result;
    }

    static List<AxlFile> byQualifiedName(Project project, String qualifiedName, GlobalSearchScope scope) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return byClassName(project, simple, scope).stream().filter(f -> f.qualifiedClassName().equals(qualifiedName)).toList();
    }

    static List<AxlFile> all(Project project, GlobalSearchScope scope) {
        List<AxlFile> result = new ArrayList<>();
        for (VirtualFile file : FileTypeIndex.getFiles(AxlFileType.INSTANCE, scope)) {
            if (PsiManager.getInstance(project).findFile(file) instanceof AxlFile axl) {
                result.add(axl);
            }
        }
        return result;
    }
}

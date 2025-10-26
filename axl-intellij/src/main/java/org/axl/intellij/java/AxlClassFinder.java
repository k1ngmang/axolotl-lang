package org.axl.intellij.java;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

/** Lets Java code see the classes of .axl files without a build. */
public final class AxlClassFinder extends PsiElementFinder {

    private final Project project;

    public AxlClassFinder(Project project) {
        this.project = project;
    }

    @Override
    public PsiClass findClass(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        PsiClass[] classes = findClasses(qualifiedName, scope);
        return classes.length == 0 ? null : classes[0];
    }

    @Override
    public PsiClass @NotNull [] findClasses(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        return AxlFiles.byQualifiedName(project, qualifiedName, scope).stream()
                .map(AxlLightClasses::classOf).filter(Objects::nonNull).toArray(PsiClass[]::new);
    }

    @Override
    public PsiClass @NotNull [] getClasses(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
        List<PsiClass> result = new ArrayList<>();
        for (AxlFile file : AxlFiles.all(project, scope)) {
            if (file.packageName().equals(psiPackage.getQualifiedName())) {
                PsiClass cls = AxlLightClasses.classOf(file);
                if (cls != null) {
                    result.add(cls);
                }
            }
        }
        return result.toArray(PsiClass[]::new);
    }
}

package org.axl.intellij.java;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.Processor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

/** Class, method and field names of .axl files, for Java completion and Go to Class. */
public final class AxlShortNamesCache extends PsiShortNamesCache {

    private final Project project;

    public AxlShortNamesCache(Project project) {
        this.project = project;
    }

    @Override
    public PsiClass @NotNull [] getClassesByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        return AxlFiles.byClassName(project, name, scope).stream()
                .map(AxlLightClasses::classOf).filter(Objects::nonNull).toArray(PsiClass[]::new);
    }

    @Override
    public String @NotNull [] getAllClassNames() {
        return AxlFiles.all(project, GlobalSearchScope.allScope(project)).stream().map(AxlFile::className).toArray(String[]::new);
    }

    @Override
    public PsiMethod @NotNull [] getMethodsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        List<PsiMethod> result = new ArrayList<>();
        for (PsiClass cls : classes(scope)) {
            result.addAll(List.of(cls.findMethodsByName(name, false)));
        }
        return result.toArray(PsiMethod.EMPTY_ARRAY);
    }

    @Override
    public PsiMethod @NotNull [] getMethodsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
        PsiMethod[] methods = getMethodsByName(name, scope);
        return methods.length > maxCount ? PsiMethod.EMPTY_ARRAY : methods;
    }

    @Override
    public PsiField @NotNull [] getFieldsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
        PsiField[] fields = getFieldsByName(name, scope);
        return fields.length > maxCount ? PsiField.EMPTY_ARRAY : fields;
    }

    @Override
    public boolean processMethodsWithName(@NotNull String name, @NotNull GlobalSearchScope scope, @NotNull Processor<? super PsiMethod> processor) {
        for (PsiMethod method : getMethodsByName(name, scope)) {
            if (!processor.process(method)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String @NotNull [] getAllMethodNames() {
        return classes(GlobalSearchScope.allScope(project)).stream()
                .flatMap(c -> java.util.Arrays.stream(c.getMethods())).map(PsiMethod::getName).distinct().toArray(String[]::new);
    }

    @Override
    public PsiField @NotNull [] getFieldsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        List<PsiField> result = new ArrayList<>();
        for (PsiClass cls : classes(scope)) {
            PsiField field = cls.findFieldByName(name, false);
            if (field != null) {
                result.add(field);
            }
        }
        return result.toArray(PsiField.EMPTY_ARRAY);
    }

    @Override
    public String @NotNull [] getAllFieldNames() {
        return classes(GlobalSearchScope.allScope(project)).stream()
                .flatMap(c -> java.util.Arrays.stream(c.getFields())).map(PsiField::getName).distinct().toArray(String[]::new);
    }

    private List<PsiClass> classes(GlobalSearchScope scope) {
        return AxlFiles.all(project, scope).stream().map(AxlLightClasses::classOf).filter(Objects::nonNull).toList();
    }
}

package org.axl.intellij.java;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

/**
 * Java code refers to the light members of an .axl file; searching for a function or a top-level
 * `val` also finds those references, so Find Usages and Rename cover Java call sites.
 */
public final class AxlReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

    public AxlReferencesSearcher() {
        super(true);
    }

    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters parameters, @NotNull Processor<? super PsiReference> consumer) {
        PsiElement target = parameters.getElementToSearch();
        PsiElement light = switch (target) {
            case AxlFunction function -> AxlLightClasses.methodOf(function);
            case AxlVariable value -> AxlLightClasses.fieldOf(value);
            default -> null;
        };
        if (light != null) {
            ReferencesSearch.search(light, parameters.getEffectiveSearchScope()).forEach(consumer);
        }
    }
}

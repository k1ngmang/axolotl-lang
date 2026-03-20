package org.axl.intellij.java;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import java.util.List;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

/**
 * Java code refers to the light classes and members of an .axl file; searching for a type, a
 * function, a method or a value also finds those references, so Find Usages and Rename cover Java
 * code.
 */
public final class AxlReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

    public AxlReferencesSearcher() {
        super(true);
    }

    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters parameters, @NotNull Processor<? super PsiReference> consumer) {
        PsiElement target = parameters.getElementToSearch();
        List<? extends PsiElement> light = switch (target) {
            case AxlFunction function -> nullable(AxlLightClasses.methodOf(function));
            case AxlVariable value -> AxlLightClasses.membersOf(value);
            case AxlTypeDeclaration declaration -> nullable(AxlLightClasses.classOf(declaration));
            default -> List.of();
        };
        for (PsiElement member : light) {
            ReferencesSearch.search(member, parameters.getEffectiveSearchScope()).forEach(consumer);
        }
    }

    private static List<PsiElement> nullable(PsiElement element) {
        return element == null ? List.of() : List.of(element);
    }
}

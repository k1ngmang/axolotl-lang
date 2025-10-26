package org.axl.intellij.refactor;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

public final class AxlRefactoringSupport extends RefactoringSupportProvider {

    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, PsiElement context) {
        return element instanceof AxlVariable variable && !variable.isTopLevel();
    }
}

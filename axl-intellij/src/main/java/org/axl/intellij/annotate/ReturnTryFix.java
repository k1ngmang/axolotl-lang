package org.axl.intellij.annotate;

import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

/** `return try e` becomes `return e`. */
final class ReturnTryFix extends PsiUpdateModCommandAction<PsiElement> {

    ReturnTryFix(PsiElement tryExpression) {
        super(tryExpression);
    }

    @Override
    public @IntentionFamilyName @NotNull String getFamilyName() {
        return "Remove `try`";
    }

    @Override
    protected void invoke(@NotNull ActionContext context, @NotNull PsiElement tryExpression, @NotNull ModPsiUpdater updater) {
        PsiElement operand = tryExpression.getLastChild();
        tryExpression.replace(operand.copy());
    }
}

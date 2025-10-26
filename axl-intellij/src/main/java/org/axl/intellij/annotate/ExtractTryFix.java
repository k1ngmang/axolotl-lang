package org.axl.intellij.annotate;

import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiParserFacade;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.HashSet;
import java.util.Set;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlNamedElement;
import org.axl.intellij.psi.AxlPsiFactory;
import org.jetbrains.annotations.NotNull;

/** Moves `try e` out of an expression into `val value = try e` before the statement. */
final class ExtractTryFix extends PsiUpdateModCommandAction<PsiElement> {

    ExtractTryFix(PsiElement tryExpression) {
        super(tryExpression);
    }

    @Override
    public @IntentionFamilyName @NotNull String getFamilyName() {
        return "Move `try` into a separate `val`";
    }

    @Override
    protected void invoke(@NotNull ActionContext context, @NotNull PsiElement tryExpression, @NotNull ModPsiUpdater updater) {
        PsiElement statement = tryExpression;
        while (statement.getParent() != null && statement.getParent().getNode().getElementType() != AxlElementTypes.BLOCK) {
            statement = statement.getParent();
        }
        if (statement.getParent() == null) {
            return;
        }
        String name = freshName(statement);
        PsiElement declaration = AxlPsiFactory.statement(tryExpression.getProject(), "val " + name + " = " + tryExpression.getText());
        PsiElement block = statement.getParent();
        PsiElement added = block.addBefore(declaration, statement);
        block.addAfter(PsiParserFacade.getInstance(tryExpression.getProject()).createWhiteSpaceFromText("\n"), added);
        tryExpression.replace(AxlPsiFactory.expression(tryExpression.getProject(), name));
    }

    private static String freshName(PsiElement statement) {
        Set<String> used = new HashSet<>();
        PsiElement function = PsiTreeUtil.findFirstParent(statement, e -> e.getNode().getElementType() == AxlElementTypes.FUNCTION);
        for (AxlNamedElement named : PsiTreeUtil.findChildrenOfType(function, AxlNamedElement.class)) {
            used.add(named.getName());
        }
        String name = "value";
        for (int i = 2; used.contains(name); i++) {
            name = "value" + i;
        }
        return name;
    }
}

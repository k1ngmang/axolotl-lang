package org.axl.intellij.annotate;

import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiParserFacade;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
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

    /**
     * Only for a `try` that runs once whenever its statement does: not in a lambda, an `attempt`, the
     * handler of a `use`, a loop condition or an `else if` condition.
     */
    static boolean isApplicable(PsiElement tryExpression) {
        PsiElement child = tryExpression;
        for (PsiElement parent = tryExpression.getParent(); parent != null; child = parent, parent = parent.getParent()) {
            IElementType type = parent.getNode().getElementType();
            if (type == AxlElementTypes.BLOCK) {
                return true;
            }
            if (type == AxlElementTypes.LAMBDA || type == AxlElementTypes.ATTEMPT || type == AxlElementTypes.USE
                    && child.getNode().getElementType() != AxlElementTypes.VARIABLE) {
                return false;
            }
            boolean elseIf = parent.getParent().getNode().getElementType() == AxlElementTypes.IF;
            if (isCondition(parent, child) && (type == AxlElementTypes.WHILE || elseIf)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isCondition(PsiElement statement, PsiElement child) {
        IElementType type = statement.getNode().getElementType();
        IElementType childType = child.getNode().getElementType();
        return (type == AxlElementTypes.WHILE || type == AxlElementTypes.IF)
                && childType != AxlElementTypes.BLOCK && childType != AxlElementTypes.IF;
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
        String indent = "";
        if (statement.getPrevSibling() instanceof PsiWhiteSpace space) {
            indent = space.getText().substring(space.getText().lastIndexOf('\n') + 1);
        }
        PsiElement added = block.addBefore(declaration, statement);
        block.addAfter(PsiParserFacade.getInstance(tryExpression.getProject()).createWhiteSpaceFromText("\n" + indent), added);
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

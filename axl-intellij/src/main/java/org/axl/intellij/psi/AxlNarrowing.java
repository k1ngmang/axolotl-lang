package org.axl.intellij.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

/**
 * Where a `val` or parameter reads as the type it was tested for, as the translator decides: in an
 * `is` arm of a `when` over it, in the branch of an `if` where the test holds, to the right of `&&`
 * (or of `||` for `!is`), and after an `if` that leaves when the test fails.
 */
final class AxlNarrowing {

    private AxlNarrowing() {}

    /** The type a variable is narrowed to at a place, or null. */
    static @Nullable AxlType typeAt(PsiElement place, AxlVariable variable) {
        if (variable.isMutable()) {
            return null;
        }
        PsiElement child = place;
        for (PsiElement parent = place.getParent(); parent != null && !(parent instanceof AxlFile); parent = parent.getParent()) {
            AxlType found = narrowedBy(parent, child, variable);
            if (found != null) {
                return found;
            }
            child = parent;
        }
        return null;
    }

    private static @Nullable AxlType narrowedBy(PsiElement parent, PsiElement child, AxlVariable variable) {
        IElementType type = parent.getNode().getElementType();
        if (type == AxlElementTypes.WHEN_ARM) {
            return armType(parent, variable);
        }
        if (type == AxlElementTypes.IF || type == AxlElementTypes.CONDITIONAL) {
            List<AxlPsiElement> parts = PsiTreeUtil.getChildrenOfTypeAsList(parent, AxlPsiElement.class);
            int branch = parts.indexOf(child);
            return branch < 1 ? null : tested(parts.getFirst(), variable, branch == 1);
        }
        if (type == AxlElementTypes.BINARY && parent.getLastChild() == child) {
            PsiElement left = parent.getFirstChild();
            if (hasToken(parent, AxolotlLexer.ANDAND)) {
                return tested(left, variable, true);
            }
            return hasToken(parent, AxolotlLexer.OROR) ? tested(left, variable, false) : null;
        }
        return type == AxlElementTypes.BLOCK ? afterLeavingIf(parent, child, variable) : null;
    }

    /** `is T ->` in a `when` whose subject is the variable or its binding. */
    private static @Nullable AxlType armType(PsiElement arm, AxlVariable variable) {
        PsiElement when = arm.getParent();
        AxlPsiElement subject = when == null ? null : PsiTreeUtil.getChildOfType(when, AxlPsiElement.class);
        if (subject != variable && !refersTo(subject, variable)) {
            return null;
        }
        List<AxlType> types = PsiTreeUtil.getChildrenOfTypeAsList(arm, AxlType.class);
        return types.size() == 1 ? types.getFirst() : null;
    }

    /** The type a condition tests the variable for, when the condition has the given outcome. */
    private static @Nullable AxlType tested(@Nullable PsiElement condition, AxlVariable variable, boolean holds) {
        if (condition == null) {
            return null;
        }
        IElementType type = condition.getNode().getElementType();
        if (type == AxlElementTypes.PARENTHESIZED) {
            return tested(PsiTreeUtil.getChildOfType(condition, AxlPsiElement.class), variable, holds);
        }
        if (type == AxlElementTypes.IS_CHECK) {
            boolean negated = hasToken(condition, AxolotlLexer.BANG);
            return negated != holds && refersTo(condition.getFirstChild(), variable)
                    ? PsiTreeUtil.getChildOfType(condition, AxlType.class) : null;
        }
        if (type == AxlElementTypes.UNARY && hasToken(condition, AxolotlLexer.BANG)) {
            return tested(condition.getLastChild(), variable, !holds);
        }
        boolean both = holds ? hasToken(condition, AxolotlLexer.ANDAND) : hasToken(condition, AxolotlLexer.OROR);
        if (type == AxlElementTypes.BINARY && both) {
            AxlType left = tested(condition.getFirstChild(), variable, holds);
            return left != null ? left : tested(condition.getLastChild(), variable, holds);
        }
        return null;
    }

    /** `if (x !is T) { return }` before the place, in the same block. */
    private static @Nullable AxlType afterLeavingIf(PsiElement block, PsiElement child, AxlVariable variable) {
        for (PsiElement sibling = child.getPrevSibling(); sibling != null; sibling = sibling.getPrevSibling()) {
            if (sibling.getNode().getElementType() != AxlElementTypes.IF) {
                continue;
            }
            List<AxlPsiElement> parts = PsiTreeUtil.getChildrenOfTypeAsList(sibling, AxlPsiElement.class);
            if (parts.size() == 2 && leaves(parts.get(1))) {
                AxlType type = tested(parts.getFirst(), variable, false);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    /** A block that ends with `return` or `throw`. */
    private static boolean leaves(PsiElement block) {
        AxlPsiElement last = null;
        for (AxlPsiElement statement : PsiTreeUtil.getChildrenOfTypeAsList(block, AxlPsiElement.class)) {
            last = statement;
        }
        if (last == null) {
            return false;
        }
        IElementType type = last.getNode().getElementType();
        return type == AxlElementTypes.RETURN || type == AxlElementTypes.EXPRESSION_STATEMENT
                && last.getFirstChild().getNode().getElementType() == AxlElementTypes.THROW;
    }

    private static boolean refersTo(@Nullable PsiElement expression, AxlVariable variable) {
        return expression instanceof AxlReferenceExpression ref && ref.qualifier() == null
                && ref.referenceName().equals(variable.getName()) && ref.getReference() != null
                && ref.getReference().resolve() == variable;
    }

    private static boolean hasToken(PsiElement element, int token) {
        return element.getNode().findChildByType(AxlTokenTypes.of(token)) != null;
    }
}

package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.Nullable;

/** A name in an expression, a type or an import: `x`, `a.b`, `a?.b`. */
public final class AxlReferenceExpression extends AxlPsiElement {

    public AxlReferenceExpression(ASTNode node) {
        super(node);
    }

    public @Nullable PsiElement identifier() {
        ASTNode last = null;
        for (ASTNode child = getNode().getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() == AxlTokenTypes.IDENTIFIER) {
                last = child;
            }
        }
        return last == null ? null : last.getPsi();
    }

    public String referenceName() {
        PsiElement identifier = identifier();
        return identifier == null ? "" : identifier.getText();
    }

    /** The expression before the dot, or null for a plain name. */
    public @Nullable PsiElement qualifier() {
        PsiElement first = getFirstChild();
        return first instanceof AxlPsiElement ? first : null;
    }

    public boolean isSafeCall() {
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.SAFE_DOT)) != null;
    }

    @Override
    public PsiReference getReference() {
        PsiElement identifier = identifier();
        if (identifier == null) {
            return null;
        }
        int start = identifier.getStartOffsetInParent();
        return new AxlReference(this, new TextRange(start, start + identifier.getTextLength()));
    }
}

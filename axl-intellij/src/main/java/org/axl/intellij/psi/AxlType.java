package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;

/** A written type such as `Result<Int, String>?`. */
public final class AxlType extends AxlPsiElement {

    public AxlType(ASTNode node) {
        super(node);
    }

    /** The type name without arguments and `?`. */
    public String name() {
        AxlReferenceExpression reference = reference();
        return reference == null ? "" : reference.getText();
    }

    public AxlReferenceExpression reference() {
        return findChildByClass(AxlReferenceExpression.class);
    }
}

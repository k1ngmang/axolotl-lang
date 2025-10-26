package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;

public final class AxlImport extends AxlPsiElement {

    public enum Kind { TYPE, FN, VAL }

    public AxlImport(ASTNode node) {
        super(node);
    }

    public Kind kind() {
        if (getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.FN)) != null) {
            return Kind.FN;
        }
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.VAL)) != null ? Kind.VAL : Kind.TYPE;
    }

    public AxlReferenceExpression reference() {
        return findChildByClass(AxlReferenceExpression.class);
    }

    public String qualifiedName() {
        AxlReferenceExpression reference = reference();
        return reference == null ? "" : reference.getText();
    }

    public String simpleName() {
        String name = qualifiedName();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}

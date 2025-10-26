package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.util.IncorrectOperationException;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.NotNull;

/** A declaration with a name: function, parameter, `val`/`var`, lambda parameter. */
public abstract class AxlNamedElement extends AxlPsiElement implements PsiNameIdentifierOwner {

    protected AxlNamedElement(ASTNode node) {
        super(node);
    }

    @Override
    public PsiElement getNameIdentifier() {
        ASTNode identifier = getNode().findChildByType(AxlTokenTypes.IDENTIFIER);
        return identifier == null ? null : identifier.getPsi();
    }

    @Override
    public String getName() {
        PsiElement identifier = getNameIdentifier();
        return identifier == null ? null : identifier.getText();
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        PsiElement identifier = getNameIdentifier();
        if (identifier != null) {
            identifier.replace(AxlPsiFactory.identifier(getProject(), name));
        }
        return this;
    }

    @Override
    public int getTextOffset() {
        PsiElement identifier = getNameIdentifier();
        return identifier == null ? super.getTextOffset() : identifier.getTextOffset();
    }
}

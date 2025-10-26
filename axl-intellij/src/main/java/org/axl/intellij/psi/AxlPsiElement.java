package org.axl.intellij.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;

public class AxlPsiElement extends ASTWrapperPsiElement {

    public AxlPsiElement(ASTNode node) {
        super(node);
    }
}

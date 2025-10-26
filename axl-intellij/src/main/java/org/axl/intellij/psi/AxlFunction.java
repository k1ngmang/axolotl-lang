package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import javax.swing.Icon;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

public final class AxlFunction extends AxlNamedElement {

    public AxlFunction(ASTNode node) {
        super(node);
    }

    public List<AxlVariable> parameters() {
        PsiElement list = findChildByType(AxlElementTypes.PARAMETER_LIST);
        return list == null ? List.of() : PsiTreeUtil.getChildrenOfTypeAsList(list, AxlVariable.class);
    }

    /** Null when the function returns nothing. */
    public @Nullable AxlType returnType() {
        return findChildByClass(AxlType.class);
    }

    public @Nullable PsiElement body() {
        PsiElement block = findChildByType(AxlElementTypes.BLOCK);
        return block != null ? block : getLastChild();
    }

    public boolean isPrivate() {
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.PRIVATE)) != null;
    }

    public boolean isMain() {
        return "main".equals(getName()) && parameters().isEmpty() && returnType() == null;
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return getName() + "(" + String.join(", ", parameters().stream().map(PsiElement::getText).toList()) + ")"
                        + (returnType() == null ? "" : " -> " + returnType().getText());
            }

            @Override
            public Icon getIcon(boolean unused) {
                return com.intellij.icons.AllIcons.Nodes.Function;
            }
        };
    }
}

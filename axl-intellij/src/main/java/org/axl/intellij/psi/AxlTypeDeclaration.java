package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import javax.swing.Icon;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;

/** A `data class` or an `interface` of a file, which Java sees as a class nested in the class of the file. */
public final class AxlTypeDeclaration extends AxlNamedElement {

    public AxlTypeDeclaration(ASTNode node) {
        super(node);
    }

    public boolean isInterface() {
        return getNode().getElementType() == AxlElementTypes.INTERFACE;
    }

    public boolean isSealed() {
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.SEALED)) != null;
    }

    /** The components of a data class, or the values an interface requires. */
    public List<AxlVariable> fields() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlVariable.class);
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return getName();
            }

            @Override
            public Icon getIcon(boolean unused) {
                return isInterface() ? com.intellij.icons.AllIcons.Nodes.Interface : com.intellij.icons.AllIcons.Nodes.Class;
            }
        };
    }
}

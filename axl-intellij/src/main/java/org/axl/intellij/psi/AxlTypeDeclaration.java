package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import javax.swing.Icon;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;

/** A `data class`, `class`, `enum` or `interface` of a file, which Java sees as a class nested in the class of the file. */
public final class AxlTypeDeclaration extends AxlNamedElement {

    public AxlTypeDeclaration(ASTNode node) {
        super(node);
    }

    public boolean isInterface() {
        return getNode().getElementType() == AxlElementTypes.INTERFACE;
    }

    public boolean isEnum() {
        return getNode().getElementType() == AxlElementTypes.ENUM;
    }

    /** The word that declares it, as users say it. */
    public String keyword() {
        var type = getNode().getElementType();
        if (type == AxlElementTypes.INTERFACE) {
            return "interface";
        }
        return type == AxlElementTypes.CLASS ? "class" : type == AxlElementTypes.ENUM ? "enum" : "data class";
    }

    public boolean isSealed() {
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.SEALED)) != null;
    }

    /** `<T, U : Bound>` after the name: types only inside the declaration. */
    public List<AxlTypeParameter> typeParameters() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlTypeParameter.class);
    }

    /** The components of a data class, the fields of a class, the constants of an enum, or the values an interface requires. */
    public List<AxlVariable> fields() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlVariable.class);
    }

    public List<AxlFunction> methods() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, AxlFunction.class);
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return getName() + AxlTypeParameter.text(typeParameters());
            }

            @Override
            public Icon getIcon(boolean unused) {
                if (isInterface()) {
                    return com.intellij.icons.AllIcons.Nodes.Interface;
                }
                return isEnum() ? com.intellij.icons.AllIcons.Nodes.Enum : com.intellij.icons.AllIcons.Nodes.Class;
            }
        };
    }
}

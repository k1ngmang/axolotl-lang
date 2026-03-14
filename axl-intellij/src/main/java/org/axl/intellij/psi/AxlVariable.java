package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import javax.swing.Icon;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A `val`, a `var`, a parameter, a lambda parameter, a loop variable or a field of a data class or an interface. */
public final class AxlVariable extends AxlNamedElement {

    public AxlVariable(ASTNode node) {
        super(node);
    }

    public @Nullable AxlType type() {
        return findChildByClass(AxlType.class);
    }

    /** The expression after `=`; null for parameters. */
    public @Nullable AxlPsiElement initializer() {
        PsiElement last = getLastChild();
        return last instanceof AxlPsiElement expression && !(last instanceof AxlType) ? expression : null;
    }

    public boolean isTopLevel() {
        return getParent() instanceof AxlFile;
    }

    public boolean isMutable() {
        return getNode().findChildByType(AxlTokenTypes.of(AxolotlLexer.VAR)) != null;
    }

    /** Locals and parameters are only visible inside their function. */
    @Override
    public @NotNull SearchScope getUseScope() {
        if (isTopLevel()) {
            return super.getUseScope();
        }
        AxlFunction function = PsiTreeUtil.getParentOfType(this, AxlFunction.class);
        return function != null ? new LocalSearchScope(function) : super.getUseScope();
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return getName() + (type() == null ? "" : ": " + type().getText());
            }

            @Override
            public Icon getIcon(boolean unused) {
                if (getParent() instanceof AxlTypeDeclaration) {
                    return com.intellij.icons.AllIcons.Nodes.Field;
                }
                return isTopLevel() ? com.intellij.icons.AllIcons.Nodes.Constant : com.intellij.icons.AllIcons.Nodes.Variable;
            }
        };
    }
}

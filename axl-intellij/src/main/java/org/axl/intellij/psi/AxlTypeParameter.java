package org.axl.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A type parameter of a data class, class, interface or function, `T` or `T : Comparable<T>`: a
 * type inside the declaration that takes it, and nowhere else.
 */
public final class AxlTypeParameter extends AxlNamedElement {

    public AxlTypeParameter(ASTNode node) {
        super(node);
    }

    /** The type after `:`; null when any type fits, nullable ones included. */
    public @Nullable AxlType bound() {
        return findChildByClass(AxlType.class);
    }

    /** The declaration it belongs to, whose text is the only place it is a type. */
    @Override
    public @NotNull SearchScope getUseScope() {
        return new LocalSearchScope(getParent());
    }

    /** `<T, U : Bound>` as it is written after a name; nothing for no parameters. */
    public static String text(List<AxlTypeParameter> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return parameters.stream().map(AxlTypeParameter::declarationText).collect(Collectors.joining(", ", "<", ">"));
    }

    private String declarationText() {
        return getName() + (bound() == null ? "" : " : " + bound().getText());
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return declarationText();
            }

            @Override
            public Icon getIcon(boolean unused) {
                return com.intellij.icons.AllIcons.Nodes.Type;
            }
        };
    }
}

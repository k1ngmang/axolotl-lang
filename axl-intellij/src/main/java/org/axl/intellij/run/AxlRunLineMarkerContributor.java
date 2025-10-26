package org.axl.intellij.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.psi.AxlFunction;
import org.jetbrains.annotations.NotNull;

/** A run icon next to `fn main()`. */
public final class AxlRunLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public Info getInfo(@NotNull PsiElement element) {
        if (element.getNode().getElementType() == AxlTokenTypes.IDENTIFIER
                && element.getParent() instanceof AxlFunction function && function.isMain()
                && function.getNameIdentifier() == element) {
            return new Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(), e -> "Run main");
        }
        return null;
    }
}

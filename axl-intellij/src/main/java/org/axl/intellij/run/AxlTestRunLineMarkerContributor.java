package org.axl.intellij.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.jetbrains.annotations.NotNull;

/** A run icon next to a class with `@Test` methods and next to each of them. */
public final class AxlTestRunLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public Info getInfo(@NotNull PsiElement element) {
        return null;
    }

    /** Telling a test needs the file's light class, which resolves the annotations: slow pass. */
    @Override
    public Info getSlowInfo(@NotNull PsiElement element) {
        if (element.getNode().getElementType() != AxlTokenTypes.IDENTIFIER) {
            return null;
        }
        if (element.getParent() instanceof AxlTypeDeclaration declaration && declaration.getNameIdentifier() == element
                && AxlTests.testClass(declaration) != null) {
            return new Info(AllIcons.RunConfigurations.TestState.Run_run, ExecutorAction.getActions(), e -> "Run tests");
        }
        if (element.getParent() instanceof AxlFunction function && function.getNameIdentifier() == element
                && AxlTests.testMethod(function) != null) {
            return new Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(), e -> "Run test");
        }
        return null;
    }
}

package org.axl.intellij.actions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.util.IncorrectOperationException;
import org.axl.intellij.psi.AxlPsiFactory;
import org.jetbrains.annotations.NotNull;

/** Replaces a Java file with static members by an .axl file next to it. */
public final class ConvertJavaToAxolotlIntention extends PsiElementBaseIntentionAction {

    @Override
    public @NotNull String getFamilyName() {
        return "Convert Java file to Axolotl";
    }

    @Override
    public @NotNull String getText() {
        return getFamilyName();
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        return element.getContainingFile() instanceof PsiJavaFile java && java.isPhysical() && JavaToAxolotl.isConvertible(java);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        PsiJavaFile java = (PsiJavaFile) element.getContainingFile();
        String text = JavaToAxolotl.convert(java);
        PsiDirectory directory = java.getContainingDirectory();
        String name = java.getName().replaceFirst("\\.java$", ".axl");
        PsiFile axl = AxlPsiFactory.file(project, text);
        axl.setName(name);
        PsiFile added = (PsiFile) directory.add(axl);
        java.delete();
        added.navigate(true);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}

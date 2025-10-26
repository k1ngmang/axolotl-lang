package org.axl.intellij.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import org.axl.intellij.AxlFileType;

/** Creates PSI from text, for refactorings and quick fixes. */
public final class AxlPsiFactory {

    private AxlPsiFactory() {}

    public static AxlFile file(Project project, String text) {
        return (AxlFile) PsiFileFactory.getInstance(project).createFileFromText("dummy.axl", AxlFileType.INSTANCE, text);
    }

    public static PsiElement identifier(Project project, String name) {
        AxlFunction function = file(project, "package p\nfn " + name + "() {}\n").functions().getFirst();
        return function.getNameIdentifier();
    }

    /** A statement as it would appear inside a function body. */
    public static PsiElement statement(Project project, String text) {
        AxlFunction function = file(project, "package p\nfn f() {\n" + text + "\n}\n").functions().getFirst();
        PsiElement block = function.body();
        return PsiTreeUtil.getChildrenOfAnyType(block, AxlPsiElement.class).getFirst();
    }

    public static PsiElement expression(Project project, String text) {
        PsiElement statement = statement(project, text);
        return statement.getFirstChild();
    }
}

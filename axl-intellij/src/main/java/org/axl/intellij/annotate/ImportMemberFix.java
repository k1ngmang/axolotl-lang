package org.axl.intellij.annotate;

import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import org.axl.intellij.psi.AxlImport;
import org.axl.intellij.psi.AxlPsiFactory;
import org.jetbrains.annotations.NotNull;

/** `import a.B.f` becomes `import fn a.B.f` (or `import val`). */
final class ImportMemberFix extends PsiUpdateModCommandAction<AxlImport> {

    private final String keyword;

    ImportMemberFix(AxlImport imp, String keyword) {
        super(imp);
        this.keyword = keyword;
    }

    @Override
    public @IntentionFamilyName @NotNull String getFamilyName() {
        return "Change to `import " + keyword + "`";
    }

    @Override
    protected void invoke(@NotNull ActionContext context, @NotNull AxlImport imp, @NotNull ModPsiUpdater updater) {
        String text = "package p\nimport " + keyword + " " + imp.qualifiedName() + "\n";
        imp.replace(AxlPsiFactory.file(imp.getProject(), text).imports().getFirst());
    }
}

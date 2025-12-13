package org.axl.intellij.actions;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import javax.swing.SwingConstants;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.java.PsiJavaTypes;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

/** Opens the Java translation of the current .axl file, read-only, in a split next to it. */
public final class ShowGeneratedJavaAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.PSI_FILE) instanceof AxlFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiFile psi = e.getData(CommonDataKeys.PSI_FILE);
        if (!(psi instanceof AxlFile file) || e.getProject() == null) {
            return;
        }
        Translator.Result result = Translator.translate(file.sourcePath(), file.getName(), file.getText(),
                new Options(false).withJavaTypes(new PsiJavaTypes(file)));
        if (result.unit() == null) {
            Messages.showWarningDialog(e.getProject(), "The file has errors; no Java was generated.", "Show Generated Java");
            return;
        }
        LightVirtualFile java = new LightVirtualFile(result.unit().className() + ".java", JavaFileType.INSTANCE,
                result.unit().javaSource());
        java.setWritable(false);
        EditorWindow window = FileEditorManagerEx.getInstanceEx(e.getProject()).getCurrentWindow();
        if (window != null) {
            window.split(SwingConstants.HORIZONTAL, true, java, true);
        } else {
            FileEditorManager.getInstance(e.getProject()).openFile(java, true);
        }
    }
}

package org.axl.intellij.annotate;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlImport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * javac, Error Prone and NullAway errors of the whole module, shown on the .axl lines. The check
 * runs `axl check` from the bundled tools, on the saved files, so results appear after a save.
 */
public final class AxlExternalAnnotator extends ExternalAnnotator<AxlExternalAnnotator.Request, List<AxlExternalAnnotator.Problem>> {

    record Request(Module module, String filePath, List<String> command) {}

    record Problem(int line, int column, boolean error, String message) {}

    private static final Pattern LINE = Pattern.compile("^(.*):(\\d+):(\\d+): (error|warning): (.*)$");

    @Override
    public @Nullable Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        VirtualFile virtualFile = file.getVirtualFile();
        Module module = ModuleUtilCore.findModuleForFile(file);
        // When the language server runs (Ultimate), it reports these errors.
        if (hasErrors || !(file instanceof AxlFile) || virtualFile == null || module == null || AxlServer.languageServerStarted(file.getProject())
                || FileDocumentManager.getInstance().isFileModified(virtualFile)) {
            return null;
        }
        List<String> command = new ArrayList<>(AxlServer.command(module, "org.axl.cli.Main"));
        command.add("check");
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(false)) {
            command.add(root.getPath());
        }
        String classpath = String.join(File.pathSeparator,
                OrderEnumerator.orderEntries(module).withoutSdk().withoutModuleSourceEntries().recursively().getPathsList().getPathList());
        if (!classpath.isEmpty()) {
            command.add("--classpath");
            command.add(classpath);
        }
        return new Request(module, virtualFile.getPath(), command);
    }

    @Override
    public @Nullable List<Problem> doAnnotate(Request request) {
        try {
            ProcessOutput output = new CapturingProcessHandler(new GeneralCommandLine(request.command()))
                    .runProcess(120_000);
            List<Problem> problems = new ArrayList<>();
            for (String line : output.getStdoutLines()) {
                Matcher m = LINE.matcher(line);
                if (m.matches() && new File(m.group(1)).getPath().equals(new File(request.filePath()).getPath())) {
                    problems.add(new Problem(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                            m.group(4).equals("error"), m.group(5)));
                }
            }
            return problems;
        } catch (com.intellij.execution.ExecutionException e) {
            return List.of();
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, List<Problem> problems, @NotNull AnnotationHolder holder) {
        Document document = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (document == null || problems == null) {
            return;
        }
        for (Problem problem : problems) {
            if (problem.line() < 1 || problem.line() > document.getLineCount()) {
                continue;
            }
            int lineEnd = document.getLineEndOffset(problem.line() - 1);
            int offset = AxlAnnotator.offsetOf(document, problem.line(), problem.column());
            var annotation = holder.newAnnotation(problem.error() ? HighlightSeverity.ERROR : HighlightSeverity.WARNING,
                    problem.message()).range(new TextRange(offset, lineEnd));
            PsiElement at = file.findElementAt(offset);
            AxlImport imp = PsiTreeUtil.getParentOfType(at, AxlImport.class);
            if (imp != null && problem.message().contains("for a function write `import fn")) {
                annotation = annotation.withFix(new ImportMemberFix(imp, "fn"));
            }
            annotation.create();
        }
    }
}

package org.axl.intellij.annotate;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
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
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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

    /** stamp: the modification count of the files when the request was made. */
    record Request(Module module, String filePath, List<String> command, long stamp) {}

    record Problem(int line, int column, boolean error, String message) {}

    /** The output of one run of `axl check`, which the files of a module share while no file changes. */
    private record Check(long stamp, CompletableFuture<List<String>> output) {}

    private static final Key<Map<List<String>, Check>> CHECKS = Key.create("axl.checks");

    private static final Pattern LINE = Pattern.compile("^(.*):(\\d+):(\\d+): (error|warning): (.*)$");

    @Override
    public @Nullable Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        VirtualFile virtualFile = file.getVirtualFile();
        Module module = ModuleUtilCore.findModuleForFile(file);
        // When the language server runs (Ultimate), it reports these errors; until it does, and if it fails, the check does.
        if (hasErrors || !(file instanceof AxlFile) || virtualFile == null || module == null || AxlServer.languageServerRuns(file.getProject())
                || FileDocumentManager.getInstance().isFileModified(virtualFile)) {
            return null;
        }
        List<String> command = new ArrayList<>(AxlServer.command(module, "org.axl.cli.Main"));
        command.add("check");
        command.addAll(sourceRoots(module, virtualFile));
        String classpath = String.join(File.pathSeparator,
                OrderEnumerator.orderEntries(module).withoutSdk().withoutModuleSourceEntries().recursively().getPathsList().getPathList());
        if (!classpath.isEmpty()) {
            command.add("--classpath");
            command.add(classpath);
        }
        return new Request(module, virtualFile.getPath(), command, VirtualFileManager.getInstance().getModificationCount());
    }

    /**
     * The source roots to check with a file: the production roots of its module, and for a test its
     * test roots too, with the production roots of the modules it depends on, where Gradle puts
     * the main sources of a project.
     */
    static List<String> sourceRoots(Module module, VirtualFile file) {
        boolean test = ProjectFileIndex.getInstance(module.getProject()).isInTestSourceContent(file);
        Set<String> roots = new LinkedHashSet<>();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(test)) {
            roots.add(root.getPath());
        }
        if (test) {
            for (VirtualFile root : OrderEnumerator.orderEntries(module).withoutSdk().withoutLibraries()
                    .withoutModuleSourceEntries().productionOnly().recursively().sources().getRoots()) {
                roots.add(root.getPath());
            }
        }
        return new ArrayList<>(roots);
    }

    @Override
    public @Nullable List<Problem> doAnnotate(Request request) {
        List<Problem> problems = new ArrayList<>();
        for (String line : output(request, AxlExternalAnnotator::run)) {
            Matcher m = LINE.matcher(line);
            if (m.matches() && new File(m.group(1)).getPath().equals(new File(request.filePath()).getPath())) {
                problems.add(new Problem(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        m.group(4).equals("error"), m.group(5)));
            }
        }
        return problems;
    }

    /**
     * The output of `axl check` for a request: of a run for the same command since the files last
     * changed, or of a new run. Every highlighting pass of every open file of a module asks, and a
     * run compiles the whole module in a new JVM.
     */
    static List<String> output(Request request, Function<List<String>, List<String>> runner) {
        Map<List<String>, Check> checks = ((UserDataHolderEx) request.module().getProject())
                .putUserDataIfAbsent(CHECKS, new ConcurrentHashMap<>());
        CompletableFuture<List<String>> mine = new CompletableFuture<>();
        Check check = checks.compute(request.command(),
                (command, old) -> old != null && old.stamp() == request.stamp() ? old : new Check(request.stamp(), mine));
        if (check.output() == mine) {
            List<String> output = List.of();
            try {
                output = runner.apply(request.command());
            } finally {
                mine.complete(output);
            }
        }
        return check.output().join();
    }

    private static List<String> run(List<String> command) {
        try {
            return new CapturingProcessHandler(new GeneralCommandLine(command)).runProcess(120_000).getStdoutLines();
        } catch (ExecutionException e) {
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

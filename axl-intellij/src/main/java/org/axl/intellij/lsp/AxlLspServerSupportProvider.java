package org.axl.intellij.lsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import java.util.Map;
import org.axl.intellij.annotate.AxlServer;
import org.jetbrains.annotations.NotNull;

/**
 * In IntelliJ IDEA Ultimate the Axolotl language server supplies module-wide javac, Error Prone and
 * NullAway diagnostics on save, and hover. Completion, navigation, highlighting and the translator's
 * errors stay with the plugin's own PSI, which also works in Community.
 */
public final class AxlLspServerSupportProvider implements LspServerSupportProvider {

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        if (!"axl".equals(file.getExtension())) {
            return;
        }
        // A server process in a test keeps the project directory open, which Windows then cannot delete.
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            starter.ensureServerStarted(new Descriptor(project));
            AxlServer.useLanguageServer(project, () -> runs(project));
        }
    }

    private static boolean runs(Project project) {
        return LspServerManager.getInstance(project).getServersForProvider(AxlLspServerSupportProvider.class).stream()
                .anyMatch(server -> server.getState() == LspServerState.Running);
    }

    private static final class Descriptor extends ProjectWideLspServerDescriptor {

        Descriptor(Project project) {
            super(project, "Axolotl");
        }

        @Override
        public boolean isSupportedFile(@NotNull VirtualFile file) {
            return "axl".equals(file.getExtension());
        }

        @Override
        public @NotNull GeneralCommandLine createCommandLine() {
            GeneralCommandLine command = new GeneralCommandLine(AxlServer.command(null, "org.axl.lsp.Main"));
            return getProject().getBasePath() == null ? command : command.withWorkDirectory(getProject().getBasePath());
        }

        /**
         * The annotator already reports the translator's errors while typing; the server adds javac's
         * on save, with the modules and classpath of the project, which it does not look for itself.
         */
        @Override
        public Object createInitializationOptions() {
            return Map.of("translatorDiagnostics", false, "modules", AxlLspModules.forServer(getProject()));
        }

        @Override
        public LspCompletionSupport getLspCompletionSupport() {
            return null;
        }

        @Override
        public boolean getLspGoToDefinitionSupport() {
            return false;
        }

        @Override
        public LspSemanticTokensSupport getLspSemanticTokensSupport() {
            return null;
        }
    }
}

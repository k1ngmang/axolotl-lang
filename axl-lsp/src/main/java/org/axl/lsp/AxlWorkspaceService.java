package org.axl.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.axl.compiler.GeneratedUnit;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.WorkspaceService;

final class AxlWorkspaceService implements WorkspaceService {

    private final Workspace workspace;

    AxlWorkspaceService(Workspace workspace) {
        this.workspace = workspace;
    }

    /** `axl.showGeneratedJava` with a document URI returns the Java generated from it. */
    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (!AxlLanguageServer.SHOW_GENERATED_JAVA.equals(params.getCommand()) || params.getArguments().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String uri = argument(params.getArguments().getFirst());
        return workspace.submit(() -> {
            GeneratedUnit unit = workspace.generated(uri);
            return unit == null ? "// The file has errors; no Java was generated.\n" : unit.javaSource();
        });
    }

    /** Arguments arrive as JSON elements. */
    private static String argument(Object value) {
        return value instanceof com.google.gson.JsonPrimitive primitive ? primitive.getAsString() : String.valueOf(value);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        List<Path> files = params.getChanges().stream().map(change -> Path.of(URI.create(change.getUri()))).toList();
        workspace.submit(() -> {
            workspace.filesChanged(files);
            return null;
        });
    }
}

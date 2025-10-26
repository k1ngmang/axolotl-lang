package org.axl.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public final class AxlLanguageServer implements LanguageServer, LanguageClientAware {

    public static final String SHOW_GENERATED_JAVA = "axl.showGeneratedJava";

    private final Workspace workspace = new Workspace();
    private final AxlTextDocumentService documents = new AxlTextDocumentService(workspace);
    private final AxlWorkspaceService workspaceService = new AxlWorkspaceService(workspace);

    // rootUri is deprecated, but clients without workspace folders still send only that.
    @SuppressWarnings("deprecation")
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        List<Path> roots = new ArrayList<>();
        if (params.getWorkspaceFolders() != null) {
            for (WorkspaceFolder folder : params.getWorkspaceFolders()) {
                roots.add(Path.of(URI.create(folder.getUri())));
            }
        } else if (params.getRootUri() != null) {
            roots.add(Path.of(URI.create(params.getRootUri())));
        }
        return workspace.submit(() -> {
            workspace.open(roots);
            return new InitializeResult(capabilities(), new ServerInfo("axl-lsp", "0.1.0-SNAPSHOT"));
        });
    }

    private static ServerCapabilities capabilities() {
        ServerCapabilities capabilities = new ServerCapabilities();
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Full);
        sync.setSave(new SaveOptions(false));
        capabilities.setTextDocumentSync(sync);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(".")));
        capabilities.setDefinitionProvider(true);
        capabilities.setHoverProvider(true);
        capabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(SemanticTokensProvider.LEGEND, true));
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(SHOW_GENERATED_JAVA)));
        return capabilities;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        workspace.close();
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documents;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        workspace.connect(client);
    }

    @Override
    public void setTrace(SetTraceParams params) {
    }
}

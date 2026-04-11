package org.axl.lsp;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public final class AxlLanguageServer implements LanguageServer, LanguageClientAware {

    public static final String SHOW_GENERATED_JAVA = "axl.showGeneratedJava";
    /** Initialization option: false when the client reports the translator's errors itself, so only javac's are sent. */
    public static final String TRANSLATOR_DIAGNOSTICS = "translatorDiagnostics";

    private final Workspace workspace = new Workspace();
    private final AxlTextDocumentService documents = new AxlTextDocumentService(workspace);
    private final AxlWorkspaceService workspaceService = new AxlWorkspaceService(workspace);
    private LanguageClient client;
    private boolean watchesFiles;

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
        var workspaceCapabilities = params.getCapabilities() == null ? null : params.getCapabilities().getWorkspace();
        var watching = workspaceCapabilities == null ? null : workspaceCapabilities.getDidChangeWatchedFiles();
        watchesFiles = watching != null && Boolean.TRUE.equals(watching.getDynamicRegistration());
        boolean translatorDiagnostics = !(params.getInitializationOptions() instanceof JsonObject options)
                || !options.has(TRANSLATOR_DIAGNOSTICS) || options.get(TRANSLATOR_DIAGNOSTICS).getAsBoolean();
        return workspace.submit(() -> {
            workspace.open(roots);
            workspace.publishTranslatorDiagnostics(translatorDiagnostics);
            String version = AxlLanguageServer.class.getPackage().getImplementationVersion();
            ServerInfo info = version != null ? new ServerInfo("axl-lsp", version) : new ServerInfo("axl-lsp");
            return new InitializeResult(capabilities(), info);
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
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(SemanticTokensProvider.LEGEND, true));
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(SHOW_GENERATED_JAVA)));
        return capabilities;
    }

    /** Files changed outside the editor change the types other files infer from them. */
    @Override
    public void initialized(InitializedParams params) {
        if (!watchesFiles || client == null) {
            return;
        }
        var watchers = List.of(new FileSystemWatcher(Either.forLeft("**/*.axl")), new FileSystemWatcher(Either.forLeft("**/*.java")));
        client.registerCapability(new RegistrationParams(List.of(new Registration("axl-watched-files",
                "workspace/didChangeWatchedFiles", new DidChangeWatchedFilesRegistrationOptions(watchers)))));
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
        this.client = client;
        workspace.connect(client);
    }

    @Override
    public void setTrace(SetTraceParams params) {
    }
}

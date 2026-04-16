package org.axl.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

final class AxlTextDocumentService implements TextDocumentService {

    private final Workspace workspace;
    private final Completions completions;
    private final Definitions definitions;
    private final Hovers hovers;
    private final Outline outline;

    AxlTextDocumentService(Workspace workspace) {
        this.workspace = workspace;
        this.completions = new Completions(workspace);
        this.definitions = new Definitions(workspace);
        this.hovers = new Hovers(workspace);
        this.outline = new Outline(workspace);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        workspace.submit(() -> {
            workspace.update(params.getTextDocument().getUri(), params.getTextDocument().getText());
            workspace.compileLater(params.getTextDocument().getUri(), true);
            return null;
        });
    }

    /** Full synchronization: the last change holds the whole text. */
    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String text = params.getContentChanges().getLast().getText();
        workspace.submit(() -> {
            workspace.update(params.getTextDocument().getUri(), text);
            return null;
        });
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        workspace.submit(() -> {
            workspace.compileLater(params.getTextDocument().getUri(), false);
            return null;
        });
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        workspace.submit(() -> {
            workspace.closeDocument(params.getTextDocument().getUri());
            return null;
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return workspace.submit(() -> Either.forLeft(completions.complete(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter())));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return workspace.submit(() -> Either.forLeft(definitions.definition(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter())));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return workspace.submit(() -> hovers.hover(params.getTextDocument().getUri(),
                params.getPosition().getLine(), params.getPosition().getCharacter()));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return workspace.submit(() -> outline.symbols(params.getTextDocument().getUri()).stream()
                .map(Either::<SymbolInformation, DocumentSymbol>forRight).toList());
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return workspace.submit(() -> {
            Document document = workspace.document(params.getTextDocument().getUri());
            return document == null ? new SemanticTokens(List.of()) : SemanticTokensProvider.tokens(document);
        });
    }
}

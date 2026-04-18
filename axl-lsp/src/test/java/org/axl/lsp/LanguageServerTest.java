package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Talks to the server over the protocol, as an editor does. */
class LanguageServerTest {

    private static final String PARSE = """
            package util

            fn parse(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else "bad: " + s
            """;
    private static final String MAIN = """
            package app
            import fn util.Parse.parse

            fn greet(name: String) -> String = Helper.salute(name)

            fn twice(s: String) -> Result<Int, String> {
                val n = try parse(s)
                val label: String = greet("x")
                return Ok(n * 2)
            }
            """;
    private static final String HELPER = """
            package app;

            public final class Helper {
                public static String salute(String name) {
                    return "hi " + name;
                }
            }
            """;

    private static final String SHAPES = """
            package app

            sealed interface Shape {
                val name: String
            }

            data class Circle(val name: String, val radius: Double) : Shape

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> shape.radius()
                else -> 0.0
            }

            fn main() {
                for (x in java.util.List.of(1, 2)) {
                    println(x is Int)
                }
            }
            """;

    @TempDir
    Path project;

    private final BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
    private LanguageServer server;
    private final CompletableFuture<Integer> exitStatus = new CompletableFuture<>();
    private Path main;

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(project.resolve("src/main/axl/app"));
        Files.createDirectories(project.resolve("src/main/axl/util"));
        Files.createDirectories(project.resolve("src/main/java/app"));
        main = project.resolve("src/main/axl/app/Main.axl");
        Files.writeString(main, MAIN);
        Files.writeString(project.resolve("src/main/axl/util/Parse.axl"), PARSE);
        Files.writeString(project.resolve("src/main/java/app/Helper.java"), HELPER);

        var toServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(toServer, 1 << 16);
        var toClient = new PipedOutputStream();
        var clientIn = new PipedInputStream(toClient, 1 << 16);
        AxlLanguageServer axl = new AxlLanguageServer(exitStatus::complete);
        var serverLauncher = AxlLanguageServer.launcher(axl, serverIn, toClient);
        axl.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();
        var clientLauncher = LSPLauncher.createClientLauncher(new Client(), clientIn, toServer);
        clientLauncher.startListening();
        server = clientLauncher.getRemoteProxy();

        InitializeParams init = new InitializeParams();
        init.setCapabilities(new ClientCapabilities());
        init.setWorkspaceFolders(List.of(new WorkspaceFolder(project.toUri().toString(), "project")));
        InitializeResult result = server.initialize(init).get(30, TimeUnit.SECONDS);
        assertEquals(List.of("."), result.getCapabilities().getCompletionProvider().getTriggerCharacters());
        assertTrue(result.getCapabilities().getHoverProvider().getLeft());
        assertTrue(result.getCapabilities().getDefinitionProvider().getLeft());
        assertTrue(result.getCapabilities().getDocumentSymbolProvider().getLeft());
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown().get(10, TimeUnit.SECONDS);
        server.exit();
        assertEquals(0, exitStatus.get(10, TimeUnit.SECONDS));
    }

    private String uri() {
        return main.toUri().toString();
    }

    private void open(String text) {
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri(), "axl", 1, text)));
    }

    private void change(String text) {
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri(), 2), List.of(new TextDocumentContentChangeEvent(text))));
    }

    /** The next diagnostics for Main.axl that satisfy a condition. */
    private List<Diagnostic> diagnosticsWhere(java.util.function.Predicate<List<Diagnostic>> condition) throws Exception {
        while (true) {
            PublishDiagnosticsParams params = diagnostics.poll(60, TimeUnit.SECONDS);
            assertNotNull(params, "no diagnostics published");
            if (params.getUri().equals(uri()) && condition.test(params.getDiagnostics())) {
                return params.getDiagnostics();
            }
        }
    }

    @Test
    void reportsRuleViolationsWhileTyping() throws Exception {
        open(MAIN.replace("return Ok(n * 2)", "return try parse(s)"));
        List<Diagnostic> found = diagnosticsWhere(d -> !d.isEmpty());
        assertEquals("AXL005", found.getFirst().getCode().getLeft());
        assertEquals(new Position(8, 11), found.getFirst().getRange().getStart());
    }

    @Test
    void reportsNullAwayErrorsOfTheModule() throws Exception {
        open(MAIN.replace("val label: String = greet(\"x\")", "val label: String? = null\n    println(label.length())"));
        List<Diagnostic> found = diagnosticsWhere(d -> !d.isEmpty());
        assertEquals("AXL102", found.getFirst().getCode().getLeft());
        assertEquals(8, found.getFirst().getRange().getStart().getLine());
    }

    @Test
    void completesNamesKeywordsAndJavaMembers() throws Exception {
        open(MAIN);
        diagnosticsWhere(List::isEmpty);
        List<String> names = complete(7, 4).stream().map(CompletionItem::getLabel).toList();
        assertTrue(names.containsAll(List.of("n", "s", "greet", "parse", "val", "println")), names.toString());

        change(MAIN.replace("    return Ok(n * 2)", "    label.\n    Integer.\n    return Ok(n * 2)"));
        List<String> instance = complete(8, 10).stream().map(CompletionItem::getInsertText).toList();
        assertTrue(instance.containsAll(List.of("length", "trim", "isEmpty")), instance.toString());
        List<String> statics = complete(9, 12).stream().map(CompletionItem::getInsertText).toList();
        assertTrue(statics.containsAll(List.of("parseInt", "valueOf", "MAX_VALUE")), statics.toString());
    }

    @Test
    void completesPipeStages() throws Exception {
        open(MAIN);
        diagnosticsWhere(List::isEmpty);
        change(MAIN.replace("    return Ok(n * 2)", "    val xs = java.util.List.of(1) |> fi\n    return Ok(n * 2)"));
        List<String> stages = complete(8, 38).stream().map(CompletionItem::getLabel).toList();
        assertTrue(stages.containsAll(List.of("filter", "mapResult", "count")), stages.toString());
    }

    @Test
    void goesToDefinitionsInAxlAndJava() throws Exception {
        open(MAIN);
        diagnosticsWhere(List::isEmpty);
        Location local = definition(7, 25);
        assertEquals(uri(), local.getUri());
        assertEquals(new Position(3, 3), local.getRange().getStart());

        Location imported = definition(6, 17);
        assertTrue(imported.getUri().endsWith("util/Parse.axl"), imported.getUri());
        assertEquals(new Position(2, 3), imported.getRange().getStart());

        Location java = definition(3, 45);
        assertTrue(java.getUri().endsWith("app/Helper.java"), java.getUri());
        assertEquals(new Position(3, 25), java.getRange().getStart());
        Location javaClass = definition(3, 37);
        assertTrue(javaClass.getUri().endsWith("app/Helper.java"), javaClass.getUri());
        assertEquals(new Position(2, 19), javaClass.getRange().getStart());
    }

    @Test
    void hoverShowsInferredTypeAndGeneratedJava() throws Exception {
        open(MAIN);
        diagnosticsWhere(List::isEmpty);
        String hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri()),
                new Position(6, 8))).get(30, TimeUnit.SECONDS).getContents().getRight().getValue();
        assertTrue(hover.contains("val n: Int"), hover);
        assertTrue(hover.contains("final var $t1 = parse(s);"), hover);
        assertTrue(hover.contains("final var n = Result.unwrap($t1);"), hover);
    }

    @Test
    void hoverInfersTypesInDeeplyNestedCode() throws Exception {
        String nested = "(".repeat(3000) + "1" + ")".repeat(3000);
        String text = "package app\n\nfn main() {\n    val x = " + nested + "\n    println(x)\n}\n";
        open(text);
        diagnosticsWhere(d -> true);
        String hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri()),
                new Position(4, 12))).get(60, TimeUnit.SECONDS).getContents().getRight().getValue();
        assertTrue(hover.contains("val x: Int"), hover);
    }

    @Test
    void outlinesDataClassesInterfacesAndFunctions() throws Exception {
        open(SHAPES);
        diagnosticsWhere(List::isEmpty);
        List<DocumentSymbol> symbols = server.getTextDocumentService().documentSymbol(new DocumentSymbolParams(
                new TextDocumentIdentifier(uri()))).get(30, TimeUnit.SECONDS).stream().map(Either::getRight).toList();
        assertEquals(List.of("Shape", "Circle", "area", "main"), symbols.stream().map(DocumentSymbol::getName).toList());
        assertEquals(List.of(SymbolKind.Interface, SymbolKind.Class, SymbolKind.Function, SymbolKind.Function),
                symbols.stream().map(DocumentSymbol::getKind).toList());
        assertEquals(List.of("name"), symbols.get(0).getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(List.of("name", "radius"), symbols.get(1).getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(new Position(6, 11), symbols.get(1).getSelectionRange().getStart());
        assertEquals(new Position(6, 40), symbols.get(1).getChildren().get(1).getSelectionRange().getStart());
    }

    @Test
    void showsGeneratedJava() throws Exception {
        open(MAIN);
        diagnosticsWhere(List::isEmpty);
        Object java = server.getWorkspaceService().executeCommand(new ExecuteCommandParams(
                AxlLanguageServer.SHOW_GENERATED_JAVA, List.of(uri()))).get(30, TimeUnit.SECONDS);
        assertTrue(String.valueOf(java).contains("@NullMarked public final class Main {"), String.valueOf(java));
    }

    @Test
    void answersRequestsItDoesNotOfferWithMethodNotFound() throws Exception {
        var rename = server.getTextDocumentService().rename(new RenameParams(new TextDocumentIdentifier(uri()), new Position(0, 0), "x"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> rename.get(30, TimeUnit.SECONDS));
        ResponseErrorException error = assertInstanceOf(ResponseErrorException.class, e.getCause());
        assertEquals(ResponseErrorCode.MethodNotFound.getValue(), error.getResponseError().getCode());
    }

    private List<CompletionItem> complete(int line, int character) throws Exception {
        return server.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(uri()),
                new Position(line, character))).get(30, TimeUnit.SECONDS).getLeft();
    }

    private Location definition(int line, int character) throws Exception {
        List<? extends Location> locations = server.getTextDocumentService().definition(new DefinitionParams(
                new TextDocumentIdentifier(uri()), new Position(line, character))).get(30, TimeUnit.SECONDS).getLeft();
        assertEquals(1, locations.size(), "definitions at " + line + ":" + character);
        return locations.getFirst();
    }

    private final class Client implements LanguageClient {
        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.add(params);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void showMessage(MessageParams params) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }
    }
}

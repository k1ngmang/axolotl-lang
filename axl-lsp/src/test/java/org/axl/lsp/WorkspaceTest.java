package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor features on a workspace, without the protocol around them. */
class WorkspaceTest {

    @TempDir
    Path project;

    private final Workspace workspace = new Workspace();
    private final Map<String, List<Diagnostic>> published = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(project.resolve("src/main/axl/app"));
        workspace.connect(new Client());
        workspace.open(List.of(project));
    }

    private String open(String name, String text) throws Exception {
        Path file = project.resolve("src/main/axl/app/" + name);
        Files.writeString(file, text);
        String uri = file.toUri().toString();
        workspace.update(uri, text);
        return uri;
    }

    @Test
    void completesOnTheEmptyLastLine() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n}\n");
        assertFalse(new Completions(workspace).complete(uri, 4, 0).isEmpty());
    }

    @Test
    void completesMembersOfCallsChainsAndContinuationLines() throws Exception {
        String uri = open("Main.axl", """
                package app

                fn name() -> String = "x"

                fn main() {
                    val s: String = "a"
                    val n = s
                        .le
                    val t = name().le
                    val u = s.trim().le
                    System.out.pr
                    val l = java.util.List.o
                }
                """);
        assertTrue(memberNames(uri, 7, 11).contains("length"), memberNames(uri, 7, 11).toString());
        assertTrue(memberNames(uri, 8, 21).contains("length"), memberNames(uri, 8, 21).toString());
        assertTrue(memberNames(uri, 9, 23).contains("length"), memberNames(uri, 9, 23).toString());
        assertTrue(memberNames(uri, 10, 17).contains("println"), memberNames(uri, 10, 17).toString());
        assertTrue(memberNames(uri, 11, 28).contains("of"), memberNames(uri, 11, 28).toString());
    }

    private List<String> memberNames(String uri, int line, int character) {
        return new Completions(workspace).complete(uri, line, character).stream().map(CompletionItem::getInsertText).toList();
    }

    @Test
    void findsTheNameRightAfterAParenthesis() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val count = 1\n    println(count)\n}\n");
        List<Location> found = new Definitions(workspace).definition(uri, 4, 12);
        assertEquals(List.of(new Position(3, 8)), found.stream().map(l -> l.getRange().getStart()).toList());
    }

    @Test
    void countsColumnsInUtf16UnitsAfterEmoji() throws Exception {
        String line = "    val s = \"\uD83D\uDE00\uD83D\uDE00\"; val y = 1; y = 2";
        String uri = open("Main.axl", "package app\n\nfn main() {\n" + line + "\n}\n");
        Document document = workspace.document(uri);
        int y = line.indexOf("y = 2");

        Diagnostic diagnostic = Workspace.toLsp(document, document.diagnostics().getFirst());
        assertEquals(new Range(new Position(3, y), new Position(3, y + 1)), diagnostic.getRange());
        String hover = new Hovers(workspace).hover(uri, 3, y).getContents().getRight().getValue();
        assertTrue(hover.contains("val y"), hover);
        assertTrue(semanticTokens(document).contains("3:" + y + ":1:variable"), semanticTokens(document).toString());
    }

    @Test
    void answersFromTheLastParsedVersionOnlyOnUnchangedLines() throws Exception {
        String text = "package app\n\nfn main() {\n    val count = 1\n    println(count)\n}\n";
        String uri = open("Main.axl", text);
        workspace.update(uri, text.replace("fn main() {\n", "fn main() {\n    val z = (\n"));

        String hover = new Hovers(workspace).hover(uri, 5, 13).getContents().getRight().getValue();
        assertTrue(hover.contains("val count"), hover);
        List<Location> found = new Definitions(workspace).definition(uri, 5, 13);
        assertEquals(List.of(new Position(4, 8)), found.stream().map(l -> l.getRange().getStart()).toList());
        assertNull(new Hovers(workspace).hover(uri, 3, 8));
        assertEquals(List.of(), new Definitions(workspace).definition(uri, 3, 8));
    }

    @Test
    void hoverTellsLocalsOfTheSameNameApart() throws Exception {
        String uri = open("Main.axl", """
                package app

                fn main() {
                    if (true) {
                        val t = "s"
                        println(t)
                    } else {
                        val t = 42
                        println(t)
                    }
                    java.util.List.of("a") |> forEach { println(it) }
                    java.util.List.of(1) |> forEach { println(it) }
                }
                """);
        assertTrue(hover(uri, 8, 16).contains("val t: int"), hover(uri, 8, 16));
        assertTrue(hover(uri, 4, 12).contains("val t: String"), hover(uri, 4, 12));
        assertTrue(hover(uri, 11, 46).contains("it: Integer"), hover(uri, 11, 46));
    }

    @Test
    void findsTheErrorNameOfAttempt() throws Exception {
        String line = "fn p(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else e -> e.getMessage()";
        String uri = open("Main.axl", "package app\n\n" + line + "\n");
        int use = line.lastIndexOf("e.getMessage");
        assertTrue(hover(uri, 2, use).contains("e: "), hover(uri, 2, use));
        List<Location> found = new Definitions(workspace).definition(uri, 2, use);
        assertEquals(List.of(new Position(2, line.indexOf("e ->"))), found.stream().map(l -> l.getRange().getStart()).toList());
    }

    @Test
    void picksTheOverloadThatFitsTheArguments() throws Exception {
        open("Util.axl", "package app\n\nfn g(s: String) -> String = s\n\nfn g(n: Int) -> Int = n\n");
        String uri = open("Main.axl", """
                package app

                fn f(a: Int, b: Int) -> Int = a + b

                fn f(a: Int) -> Int = a

                fn main() {
                    println(f(1))
                    println(f(1, 2))
                    println(Util.g(1))
                }
                """);
        assertEquals(List.of(4), definitionLines(uri, 7, 12));
        assertEquals(List.of(2), definitionLines(uri, 8, 12));
        assertTrue(hover(uri, 7, 12).contains("fn f(a: Int) -> Int"), hover(uri, 7, 12));
        assertEquals(List.of(4), definitionLines(uri, 9, 17));
    }

    @Test
    void compilesTheModuleAroundAFileThatDoesNotTranslate() throws Exception {
        Path broken = project.resolve("src/main/axl/app/Broken.axl");
        Files.writeString(broken, "package app\n\nfn broken( {\n");
        String text = "package app\n\nfn main() {\n    val n: Int = \"not an int\"\n    println(n)\n}\n";
        String uri = open("Main.axl", text);
        workspace.saved(uri);
        assertEquals(List.of("AXL100"), published.get(uri).stream().map(d -> d.getCode().getLeft()).toList());

        workspace.update(uri, text.replace("\"not an int\"", "1"));
        workspace.saved(uri);
        assertEquals(List.of(), published.get(uri));
    }

    @Test
    void movesCompileErrorsWithTheTextUntilTheNextSave() throws Exception {
        String text = "package app\n\nfn main() {\n    val n: Int = \"s\"\n}\n";
        String uri = open("Main.axl", text);
        workspace.saved(uri);
        assertEquals(List.of(3), errorLines(uri));

        workspace.update(uri, text.replace("fn main() {\n", "fn main() {\n    println(1)\n    println(2)\n"));
        assertEquals(List.of(5), errorLines(uri));
        workspace.update(uri, text.replace("\"s\"", "\"t\""));
        assertEquals(List.of(), errorLines(uri));
    }

    @Test
    void reportsClassesDeclaredByTwoFiles() throws Exception {
        String first = open("Main.axl", "package app\n\nfn main() {\n    println(1)\n}\n");
        Path copy = project.resolve("src/main/axl/old/Main.axl");
        Files.createDirectories(copy.getParent());
        String text = "package app\n\nfn main() {\n    val n: Int = \"s\"\n}\n";
        Files.writeString(copy, text);
        String second = copy.toUri().toString();
        workspace.update(second, text);
        workspace.saved(second);

        String message = "the class `app.Main` is declared by several files: app/Main.axl, old/Main.axl";
        for (String uri : List.of(first, second)) {
            List<String> errors = published.get(uri).stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
                    .map(Diagnostic::getMessage).toList();
            assertEquals(List.of(message), errors, uri);
        }
    }

    @Test
    void takesOnlyFilesInTheirPackageDirectoryIntoALooseModule(@TempDir Path loose) throws Exception {
        Files.createDirectories(loose.resolve("app"));
        Files.createDirectories(loose.resolve("util"));
        Files.createDirectories(loose.resolve("old/copy"));
        Files.createDirectories(loose.resolve("old-copies/app"));
        Files.writeString(loose.resolve("util/Strings.axl"), "package util\n\nfn shout(s: String) -> String = s\n");
        Files.writeString(loose.resolve("old/copy/Main.axl"), "package app\n\nfn main() {\n}\n");
        Files.writeString(loose.resolve("old-copies/app/Main.axl"), "package app\n\nfn main() {\n}\n");
        Path main = loose.resolve("app/Main.axl");
        String text = "package app\n\nfn main() {\n}\n";
        Files.writeString(main, text);
        workspace.update(main.toUri().toString(), text);

        Document document = workspace.document(main.toUri().toString());
        assertEquals(loose, document.module.root());
        assertEquals(List.of(main, loose.resolve("util/Strings.axl")),
                workspace.documentsOf(document.module).stream().map(d -> d.path).toList());
    }

    @Test
    void infersValuesFromOtherFilesOfTheModule() throws Exception {
        Files.writeString(project.resolve("src/main/axl/app/Names.axl"),
                "package app\n\nfn greeting() -> String = \"hi\"\nval words = java.util.List.of(\"a\")\n");
        String main = open("Main.axl", "package app\n\nval g = Names.greeting()\nval w = Names.words\n\nfn main() {\n"
                + "    println(g.length() + w.size())\n}\n");
        assertEquals(List.of(), published.get(main));

        open("Names.axl", "package app\n\nfn greeting() -> String = \"hi\"\nfn count() -> Int = 1\n");
        String other = open("Other.axl", "package app\n\nval c = Names.count()\n");
        assertEquals(List.of(), published.get(other));
    }

    @Test
    void clearsDiagnosticsOfAClosedDocument() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val y = 1\n    y = 2\n}\n");
        assertFalse(published.get(uri).isEmpty());
        workspace.closeDocument(uri);
        assertEquals(List.of(), published.get(uri));
    }

    @Test
    void classifiesQualifiedNamesAndLambdaParameters() throws Exception {
        String uri = open("Main.axl", """
                package app.util
                import java.util.List
                import fn util.Parse.parse

                fn f(xs: java.util.List<Int>) -> Int {
                    val g = { x: Int -> x * 2 }
                    return java.util.List.of(1).size()
                }
                """);
        List<String> tokens = semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("0:8:3:namespace", "0:12:4:namespace", "1:7:4:namespace", "1:17:4:type",
                "2:10:4:namespace", "2:15:5:namespace", "2:21:5:function", "4:9:4:namespace", "4:19:4:type",
                "4:33:3:type", "5:24:1:variable", "6:21:4:type", "6:26:2:function")), tokens.toString());
    }

    @Test
    void classifiesMethodReferencesAndAttemptErrorNames() throws Exception {
        String uri = open("Main.axl", """
                package app

                fn f(s: String) -> Result<Int, String> {
                    val r = attempt Integer.parseInt(s) else e -> 0
                    val m = String::valueOf
                    return Ok(1)
                }
                """);
        List<String> tokens = semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("3:45:1:parameter", "4:20:7:function")), tokens.toString());
    }

    @Test
    void splitsBlockCommentsAtWindowsLineEnds() throws Exception {
        String uri = open("Main.axl", "package app\r\n/* a\r\n b */\r\nfn main() {\r\n}\r\n");
        List<String> tokens = semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("1:0:4:comment", "2:0:5:comment")), tokens.toString());
    }

    private String hover(String uri, int line, int character) {
        return new Hovers(workspace).hover(uri, line, character).getContents().getRight().getValue();
    }

    private List<Integer> definitionLines(String uri, int line, int character) {
        return new Definitions(workspace).definition(uri, line, character).stream().map(l -> l.getRange().getStart().getLine()).toList();
    }

    private List<Integer> errorLines(String uri) {
        return published.get(uri).stream().map(d -> d.getRange().getStart().getLine()).toList();
    }

    /** Semantic tokens as line:start:length:type. */
    private static List<String> semanticTokens(Document document) {
        List<Integer> data = SemanticTokensProvider.tokens(document).getData();
        List<String> result = new ArrayList<>();
        int line = 0;
        int start = 0;
        for (int i = 0; i < data.size(); i += 5) {
            start = data.get(i) == 0 ? start + data.get(i + 1) : data.get(i + 1);
            line += data.get(i);
            result.add(line + ":" + start + ":" + data.get(i + 2) + ":" + SemanticTokensProvider.TYPES.get(data.get(i + 3)));
        }
        return result;
    }

    private final class Client implements LanguageClient {
        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            published.put(params.getUri(), params.getDiagnostics());
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

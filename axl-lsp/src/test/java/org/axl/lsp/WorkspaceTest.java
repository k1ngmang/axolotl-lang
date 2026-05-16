package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.axl.compiler.ast.Pos;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor features on a workspace, without the protocol around them. */
class WorkspaceTest {

    private static final String SHAPES = """
            package app

            sealed interface Shape {
                val name: String
            }

            data class Circle(val name: String, val radius: Double) : Shape

            data class Square(val name: String, val side: Double) : Shape

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> shape.radius()
                is Square -> {
                    val side = shape.side()
                    side * side
                }
            }

            fn size(n: Int) -> String = when (n) {
                1, 2 -> "few"
                else -> "many"
            }

            fn describe(shape: Shape) -> String {
                if (shape is Circle) {
                    return shape.name()
                }
                if (shape !is Square) {
                    return "other"
                }
                val total = when (val e = shape) {
                    is Square -> e.side()
                    else -> 0.0
                }
                for (x in java.util.List.of(1, 2)) {
                    println(x + total)
                }
                return shape.name()
            }
            """;

    private static final String COUNTER = """
            package app

            enum Color { RED, GREEN, BLUE }

            class Counter(val name: String, var step: Int) {
                var count: Int = 0

                fn next() -> Int {
                    count += step
                    return count
                }

                fn label() -> String = name
            }

            fn pick(n: Int) -> Int = if (n > 0) n else throw IllegalStateException("x")

            fn main() {
                val c = Counter("c", 1)
                println(c.next())
            }
            """;

    @TempDir
    Path project;

    private final Workspace workspace = new Workspace();
    private final Map<String, List<Diagnostic>> published = new ConcurrentHashMap<>();
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final AtomicInteger publications = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(project.resolve("src/main/axl/app"));
        workspace.connect(new Client());
        workspace.open(List.of(project));
    }

    @AfterEach
    void stop() {
        workspace.close();
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

    @Test
    void completesNamesWhereNoMemberIsTyped() throws Exception {
        String line = "    val t = s.length ";
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val s = \"abc\"\n" + line + "\n\n}\n");
        for (int[] at : List.of(new int[] {5, 0}, new int[] {4, line.length()})) {
            List<String> labels = new Completions(workspace).complete(uri, at[0], at[1]).stream()
                    .map(CompletionItem::getLabel).toList();
            assertTrue(labels.contains("val") && labels.contains("s") && !labels.contains("length()"), labels.toString());
        }
        assertTrue(memberNames(uri, 4, line.length() - 1).contains("length"), memberNames(uri, 4, line.length() - 1).toString());
    }

    @Test
    void completesMembersOfGenericResultsAndOfTheFittingOverload() throws Exception {
        Files.createDirectories(project.resolve("src/main/java/app"));
        Files.writeString(project.resolve("src/main/java/app/Pick.java"), """
                package app;

                public final class Pick {
                    public static String pick(String s) { return s; }
                    public static Integer pick(int n) { return n; }
                }
                """);
        workspace.open(List.of(project));
        String uri = open("Main.axl", """
                package app

                fn main() {
                    val l: java.util.List<String> = java.util.List.of("a")
                    val m = java.util.Map.of("k", 1)
                    val a = l.get(0).le
                    val b = m.get("k").intV
                    val c = Pick.pick(1).intV
                    val d = Pick.pick("s").le
                }
                """);
        assertTrue(memberNames(uri, 5, 23).contains("length"), memberNames(uri, 5, 23).toString());
        assertTrue(memberNames(uri, 6, 27).contains("intValue"), memberNames(uri, 6, 27).toString());
        assertTrue(memberNames(uri, 7, 29).contains("intValue"), memberNames(uri, 7, 29).toString());
        assertTrue(memberNames(uri, 8, 29).contains("length"), memberNames(uri, 8, 29).toString());
    }

    @Test
    void completesNestedClassesAndTheirMembers() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val s = Thread.State.N\n    val t = Thread.S\n}\n");
        assertTrue(memberNames(uri, 3, 26).contains("NEW"), memberNames(uri, 3, 26).toString());
        assertTrue(memberNames(uri, 4, 20).contains("State"), memberNames(uri, 4, 20).toString());
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
    void completesNamesDeclaredEarlierOnTheLineBeingEdited() throws Exception {
        String text = """
                package app

                data class Circle(val radius: Double)

                fn main(sh: Any) {
                    val xs = java.util.List.of(1, 2)
                    val ys = xs |> map { v -> v }
                    for (q in xs) { println(q) }
                    if (sh is Circle) { println(sh) }
                }
                """;
        String uri = open("Main.axl", text);
        for (String line : List.of("map { v -> v", "println(q", "println(sh")) {
            workspace.update(uri, text.replace(line, line + "."));
            int number = text.substring(0, text.indexOf(line)).split("\n", -1).length - 1;
            int character = workspace.document(uri).lines.get(number).indexOf(line) + line.length() + 1;
            List<String> names = memberNames(uri, number, character);
            assertTrue(names.contains(line.endsWith("sh") ? "radius" : "intValue"), line + ": " + names);
        }
    }

    @Test
    void completesInTheScopeOfTheCursorWhenTwoPlacesAreEdited() throws Exception {
        String text = """
                package app

                fn first() {
                    val word = "s"
                    println(word.length())
                }

                fn second() {
                    val xs = java.util.List.of(1, 2)
                    println(xs.size())
                }
                """;
        String uri = open("Main.axl", text);
        workspace.update(uri, text.replace("word.length())", "word.length(")
                .replace("println(xs.size())\n", "println(xs.size())\n    xs.\n"));
        assertTrue(memberNames(uri, 10, 7).contains("size"), memberNames(uri, 10, 7).toString());
        List<String> labels = new Completions(workspace).complete(uri, 10, 4).stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.contains("xs") && !labels.contains("word"), labels.toString());
    }

    @Test
    void letsTestsUseTheMainSourcesWithoutADescription() throws Exception {
        Files.writeString(project.resolve("src/main/axl/app/Util.axl"), "package app\n\nfn twice(x: Int) -> Int = x * 2\n");
        Path test = Files.createDirectories(project.resolve("src/test/axl/app")).resolve("UtilTest.axl");
        String text = "package app\n\nfn check() -> Int = Util.twice(2)\n";
        Files.writeString(test, text);
        workspace.open(List.of(project));
        String uri = test.toUri().toString();
        workspace.update(uri, text);
        assertEquals(List.of(), workspace.document(uri).diagnostics());
        workspace.saved(uri);
        assertEquals(List.of(), published.get(uri));
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
        assertTrue(hover(uri, 8, 16).contains("val t: Int"), hover(uri, 8, 16));
        assertTrue(hover(uri, 4, 12).contains("val t: String"), hover(uri, 4, 12));
        assertTrue(hover(uri, 11, 46).contains("it: Int"), hover(uri, 11, 46));
    }

    @Test
    void hoverShowsTheInferredTypeOfATopLevelValue() throws Exception {
        String uri = open("Main.axl", "package app\n\nval greeting = \"hi\"\n\nfn main() {\n    println(greeting)\n}\n");
        assertTrue(hover(uri, 5, 13).contains("val greeting: String"), hover(uri, 5, 13));
        assertTrue(hover(uri, 2, 5).contains("val greeting: String"), hover(uri, 2, 5));
        assertTrue(hover(uri, 2, 5).contains("Generated Java"), hover(uri, 2, 5));
    }

    @Test
    void infersTypesWhileAnotherLineHasAnError() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val s = \"abc\"\n    val n = s.le\n"
                + "    val k = 1\n    k = 2\n}\n");
        assertTrue(memberNames(uri, 4, 16).contains("length"), memberNames(uri, 4, 16).toString());
        assertTrue(hover(uri, 3, 8).contains("val s: String"), hover(uri, 3, 8));
    }

    @Test
    void infersTypesFromAnotherFileWithAnError() throws Exception {
        Files.writeString(project.resolve("src/main/axl/app/Other.axl"),
                "package app\n\nval x = \"a\"\n\nfn bad() {\n    val k = 1\n    k = 2\n}\n");
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val s = Other.x\n    val t = Other.missing\n}\n");
        assertTrue(hover(uri, 3, 8).contains("val s: String"), hover(uri, 3, 8));
        assertTrue(hover(uri, 4, 8).contains("val t: ?"), hover(uri, 4, 8));
    }

    @Test
    void knowsLambdaParametersOfTopLevelValues() throws Exception {
        String uri = open("Main.axl", """
                package app

                val lengths = java.util.List.of("a") |> map { it.length() }
                val f: java.util.function.Function<String, Int> = { s -> s.le }
                """);
        assertTrue(hover(uri, 2, 47).contains("it: String"), hover(uri, 2, 47));
        assertTrue(memberNames(uri, 3, 61).contains("length"), memberNames(uri, 3, 61).toString());
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
    void picksTheDeclaredOverloadAndTheOneThatFitsANamedArgument() throws Exception {
        String uri = open("Main.axl", """
                package app

                fn f(s: String) -> String = s

                fn f(n: Int) -> Int = n

                fn main() {
                    val k = 2
                    val t: String = "t"
                    println(f(k))
                    println(f(t))
                }
                """);
        assertTrue(hover(uri, 4, 3).contains("fn f(n: Int) -> Int"), hover(uri, 4, 3));
        assertEquals(List.of(4), definitionLines(uri, 4, 3));
        assertEquals(List.of(4), definitionLines(uri, 9, 12));
        assertEquals(List.of(2), definitionLines(uri, 10, 12));
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
    void compilesOffTheRequestThread() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val n: Int = \"s\"\n}\n");
        workspace.submit(() -> {
            workspace.compileLater(uri, true);
            assertEquals(List.of(), published.get(uri));
            return null;
        }).get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (published.get(uri).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of(3), errorLines(uri));
    }

    @Test
    void findsTheFirstTokenFromAPosition() throws Exception {
        Document document = workspace.document(open("Main.axl", "package app\n\nfn main() {}\n"));
        assertEquals("package", document.tokens.get(document.firstTokenFrom(new Pos(1, 1))).getText());
        assertEquals("app", document.tokens.get(document.firstTokenFrom(new Pos(1, 9))).getText());
        assertEquals("main", document.tokens.get(document.firstTokenFrom(new Pos(3, 4))).getText());
        assertEquals(document.tokens.size(), document.firstTokenFrom(new Pos(9, 1)));
    }

    @Test
    void translatesOnlyTheNewestOfTheChangesWaiting() throws Exception {
        String uri = open("Main.axl", "package app\n");
        CountDownLatch busy = new CountDownLatch(1);
        workspace.submit(() -> busy.await(10, TimeUnit.SECONDS));
        publications.set(0);
        workspace.changeLater(uri, "package app\n\nfn a() {}\n");
        workspace.changeLater(uri, "package app\n\nfn b() {}\n");
        busy.countDown();
        workspace.submit(() -> null).get(10, TimeUnit.SECONDS);

        assertEquals("package app\n\nfn b() {}\n", workspace.document(uri).text);
        assertEquals(1, publications.get());
    }

    @Test
    void deletesItsTemporaryDirectoriesOnClose() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> before = temporaryDirectories(tmp);
        Workspace other = new Workspace();
        other.open(List.of(project));
        Path file = Files.writeString(project.resolve("src/main/axl/app/Main.axl"), "package app\n");
        other.update(file.toUri().toString(), "package app\n");
        List<Path> created = temporaryDirectories(tmp).stream().filter(p -> !before.contains(p)).toList();
        assertFalse(created.isEmpty());

        other.close();
        assertTrue(created.stream().noneMatch(Files::exists), created.toString());
    }

    private static List<Path> temporaryDirectories(Path tmp) throws Exception {
        try (var files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("axl-lsp")).toList();
        }
    }

    @Test
    void goesToMembersOfATypeWhoseFileIsOnlyInTheEditor() throws Exception {
        String uri = project.resolve("src/main/axl/app/Counter.axl").toUri().toString();
        workspace.update(uri, COUNTER);
        List<Location> found = new Definitions(workspace).definition(uri, 19, 15);
        assertEquals(List.of(new Position(7, 7)), found.stream().map(l -> l.getRange().getStart()).toList());
        String hover = new Hovers(workspace).hover(uri, 19, 15).getContents().getRight().getValue();
        assertTrue(hover.contains("fn next() -> Int"), hover);
    }

    @Test
    void logsTheFailuresOfNotifications() throws Exception {
        workspace.submitLogged("Opening x", () -> {
            throw new IllegalStateException("broken");
        });
        workspace.submit(() -> null).get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (logged.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(logged.getFirst().startsWith("Opening x failed") && logged.getFirst().contains("broken"), logged.toString());
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
    void goesToDefinitionsInOtherFilesOfALooseModule(@TempDir Path loose) throws Exception {
        Files.createDirectories(loose.resolve("app"));
        Files.createDirectories(loose.resolve("util"));
        Path parse = loose.resolve("util/Parse.axl");
        Files.writeString(parse, "package util\n\nfn parse(s: String) -> Int = 1\n");
        Path main = loose.resolve("app/Main.axl");
        String text = "package app\nimport fn util.Parse.parse\n\nfn main() {\n    println(parse(\"1\"))\n}\n";
        Files.writeString(main, text);
        workspace.update(main.toUri().toString(), text);

        List<Location> found = new Definitions(workspace).definition(main.toUri().toString(), 4, 13);
        assertEquals(List.of(parse.toUri().toString()), found.stream().map(Location::getUri).toList());
        assertEquals(new Position(2, 3), found.getFirst().getRange().getStart());
    }

    @Test
    void goesToDefinitionsFromTheSegmentsOfAnImport() throws Exception {
        Path parse = project.resolve("src/main/axl/util/Parse.axl");
        Files.createDirectories(parse.getParent());
        Files.writeString(parse, "package util\n\nfn parse(s: String) -> Int = 1\n");
        String uri = open("Main.axl", "package app\nimport fn util.Parse.parse\n\nfn main() {\n    println(parse(\"1\"))\n}\n");

        List<Location> function = new Definitions(workspace).definition(uri, 1, 22);
        assertEquals(List.of(parse.toUri().toString()), function.stream().map(Location::getUri).toList());
        assertEquals(new Position(2, 3), function.getFirst().getRange().getStart());
        List<Location> file = new Definitions(workspace).definition(uri, 1, 16);
        assertEquals(List.of(new Position(0, 0)), file.stream().map(l -> l.getRange().getStart()).toList());
    }

    @Test
    void readsThePackageOfLooseFilesWithTheLexer(@TempDir Path loose) throws Exception {
        Files.createDirectories(loose.resolve("app"));
        Files.createDirectories(loose.resolve("other"));
        Files.writeString(loose.resolve("other/Old.axl"), "/*\npackage other\n*/\npackage app\n\nfn old() {\n}\n");
        Path main = loose.resolve("app/Main.axl");
        Files.writeString(main, "package app\n\nfn main() {\n}\n");
        workspace.update(main.toUri().toString(), Files.readString(main));

        Module module = workspace.document(main.toUri().toString()).module;
        assertEquals(List.of(main), workspace.documentsOf(module).stream().map(d -> d.path).toList());
        assertEquals("\u00fctil.t\u00e9xt", Workspace.packageName("// package x\npackage \u00fctil.t\u00e9xt\n"));
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

    private static final String USES_GREETING = "package app\n\nval g = Names.greeting()\n\nfn main() {\n"
            + "    println(g.length())\n}\n";

    @Test
    void infersFromTheSavedTextOfAFileClosedWithoutSaving() throws Exception {
        String names = open("Names.axl", "package app\n\nfn greeting() -> String = \"hi\"\n");
        workspace.update(names, "package app\n\nfn greeting() -> Int = 1\n");
        workspace.closeDocument(names);

        String main = open("Main.axl", USES_GREETING);
        workspace.saved(main);
        assertEquals(List.of(), published.get(main));
    }

    @Test
    void infersFromFilesChangedOutsideTheEditor() throws Exception {
        Path names = project.resolve("src/main/axl/app/Names.axl");
        Files.writeString(names, "package app\n\nfn greeting() -> Long = 1L\n");
        String main = open("Main.axl", USES_GREETING);

        Files.writeString(names, "package app\n\nfn greeting() -> String = \"hi\"\n");
        workspace.filesChanged(List.of(names));
        workspace.saved(main);
        assertEquals(List.of(), published.get(main));
    }

    @Test
    void forgetsTheClassOfAFileWhosePackageChanged() throws Exception {
        String names = open("Names.axl", "package app\n\nfn greeting() -> String = \"hi\"\n");
        String main = open("Main.axl", USES_GREETING);
        assertEquals(List.of(), published.get(main));

        workspace.update(names, "package other\n\nfn greeting() -> String = \"hi\"\n");
        workspace.update(main, USES_GREETING);
        assertFalse(published.get(main).isEmpty());
    }

    @Test
    void sendsOnlyJavacErrorsToAClientThatShowsTheTranslatorErrors() throws Exception {
        workspace.publishTranslatorDiagnostics(false);
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val y = 1\n    y = 2\n}\n");
        assertEquals(List.of(), published.get(uri));

        workspace.update(uri, "package app\n\nfn main() {\n    val n: Int = \"s\"\n}\n");
        workspace.saved(uri);
        assertEquals(List.of("AXL100"), published.get(uri).stream().map(d -> d.getCode().getLeft()).toList());
    }

    @Test
    void reusesTranslationsAndTheJavaModelWhileTheirInputIsUnchanged() throws Exception {
        Files.writeString(project.resolve("src/main/axl/app/Names.axl"), "package app\n\nfn greeting() -> String = \"hi\"\n");
        String text = "package app\n\nfn main() {\n    val s = Names.greeting()\n}\n";
        String uri = open("Main.axl", text);
        Module module = workspace.document(uri).module;
        Document names = workspace.documentsOf(module).getLast();
        JavaModel model = workspace.javaModel(module);

        workspace.update(uri, text + "// note\n");
        assertSame(names, workspace.documentsOf(module).getLast());
        assertSame(model, workspace.javaModel(module));
        workspace.update(uri, text.replace("val s", "val t"));
        assertNotSame(model, workspace.javaModel(module));
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
    void classifiesTheKeywordsOfLoopsDataClassesAndWhen() throws Exception {
        String uri = open("Main.axl", """
                package app

                sealed interface Shape
                data class Circle(val radius: Double) : Shape

                fn f(shape: Shape, xs: java.util.List<Int>, data: Int, in: Int) -> Int {
                    for (x in xs) {
                        println(x is Int)
                    }
                    return when (shape) {
                        is Circle -> data + in
                        else -> 0
                    }
                }
                """);
        List<String> tokens = semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("2:0:6:keyword", "2:7:9:keyword", "3:0:4:keyword", "3:5:5:keyword",
                "6:4:3:keyword", "6:9:1:variable", "6:11:2:keyword", "7:18:2:keyword", "9:11:4:keyword",
                "10:8:2:keyword", "10:21:4:variable", "10:28:2:variable")), tokens.toString());
    }

    @Test
    void completesTheKeywordsOfLoopsDataClassesAndWhen() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n\n}\n\n");
        List<String> labels = new Completions(workspace).complete(uri, 3, 0).stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("for", "when", "throw")), labels.toString());
        labels = new Completions(workspace).complete(uri, 6, 0).stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("data", "class", "interface", "sealed")), labels.toString());
    }

    @Test
    void completesOnlyTypesAfterIs() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        workspace.update(uri, SHAPES.replace("is Square -> {", "is Sq -> {"));
        List<String> labels = new Completions(workspace).complete(uri, 12, 9).stream().map(CompletionItem::getLabel).toList();
        assertEquals(List.of("Shape", "Circle", "Square"), labels);
    }

    @Test
    void completesOnlyValInAnInterface() throws Exception {
        String uri = open("Shape.axl", "package app\n\nsealed interface Shape {\n    \n}\n");
        List<String> labels = new Completions(workspace).complete(uri, 3, 4).stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.contains("val") && !labels.contains("var") && !labels.contains("fn"), labels.toString());
    }

    @Test
    void acceptsAFileWithDataClassesInterfacesLoopsAndWhen() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        assertEquals(List.of(), errorLines(uri));
    }

    @Test
    void completesTheMembersOfADataClassWhereATestNarrowsAValue() throws Exception {
        String inWhen = open("Shapes.axl", SHAPES.replace("shape.radius()", "shape.ra"));
        assertTrue(memberNames(inWhen, 11, 25).contains("radius"), memberNames(inWhen, 11, 25).toString());
        assertFalse(memberNames(inWhen, 11, 25).contains("side"), memberNames(inWhen, 11, 25).toString());
        String inIf = open("Shapes.axl", SHAPES.replaceFirst("return shape\\.name\\(\\)", "return shape.ra"));
        assertTrue(memberNames(inIf, 25, 23).contains("radius"), memberNames(inIf, 25, 23).toString());
    }

    @Test
    void knowsTheBindingOfAWhenAndTheVariableOfAFor() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        assertTrue(hover(uri, 31, 21).contains("val e: Square"), hover(uri, 31, 21));
        assertTrue(hover(uri, 35, 16).contains("val x"), hover(uri, 35, 16));
    }

    @Test
    void hoversAndGoesToDataClassesAndInterfaces() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        assertTrue(hover(uri, 10, 15).contains("sealed interface Shape"), hover(uri, 10, 15));
        assertTrue(hover(uri, 11, 7).contains("data class Circle(val name: String, val radius: Double) : Shape"),
                hover(uri, 11, 7));
        assertEquals(List.of(2), definitionLines(uri, 10, 15));
        assertEquals(List.of(6), definitionLines(uri, 11, 7));
    }

    @Test
    void outlinesClassMembersAndEnumConstants() throws Exception {
        String uri = open("Counter.axl", COUNTER);
        List<DocumentSymbol> symbols = new Outline(workspace).symbols(uri);
        assertEquals(List.of("Color", "Counter", "pick", "main"), symbols.stream().map(DocumentSymbol::getName).toList());
        DocumentSymbol color = symbols.get(0);
        assertEquals(List.of("RED", "GREEN", "BLUE"), color.getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(SymbolKind.EnumMember, color.getChildren().get(1).getKind());
        assertEquals(new Position(2, 18), color.getChildren().get(1).getSelectionRange().getStart());
        DocumentSymbol counter = symbols.get(1);
        assertEquals(List.of("name", "step", "count", "next", "label"),
                counter.getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(List.of(SymbolKind.Field, SymbolKind.Field, SymbolKind.Field, SymbolKind.Method, SymbolKind.Method),
                counter.getChildren().stream().map(DocumentSymbol::getKind).toList());
    }

    @Test
    void outlinesDeclarationsWithTheirWholeText() throws Exception {
        String uri = open("Counter.axl", COUNTER);
        List<DocumentSymbol> symbols = new Outline(workspace).symbols(uri);
        DocumentSymbol counter = symbols.get(1);
        assertEquals(new Range(new Position(4, 0), new Position(13, 1)), counter.getRange());
        assertEquals(new Range(new Position(7, 4), new Position(10, 5)), counter.getChildren().get(3).getRange());
        assertEquals(new Range(new Position(12, 4), new Position(12, 31)), counter.getChildren().get(4).getRange());
        assertEquals(new Range(new Position(17, 0), new Position(20, 1)), symbols.get(3).getRange());
    }

    @Test
    void hoversAndGoesToClassesAndEnums() throws Exception {
        String uri = open("Counter.axl", COUNTER);
        assertTrue(hover(uri, 18, 14).contains("class Counter(val name: String, var step: Int)"), hover(uri, 18, 14));
        assertEquals(List.of(4), definitionLines(uri, 18, 14));
        assertTrue(hover(uri, 8, 8).contains("var count: Int"), hover(uri, 8, 8));
        assertEquals(List.of(5), definitionLines(uri, 8, 8));
    }

    @Test
    void completesClassMembersAfterADotAndTheNewKeywords() throws Exception {
        String uri = open("Counter.axl", COUNTER.replace("c.next()", "c.ne"));
        assertTrue(memberNames(uri, 19, 16).contains("next"), memberNames(uri, 19, 16).toString());
        List<String> names = new Completions(workspace).complete(uri, 17, 4).stream().map(i -> i.getLabel()).toList();
        assertTrue(names.containsAll(List.of("enum", "class", "Counter", "Color")), names.toString());
    }

    @Test
    void colorsTheNewKeywords() throws Exception {
        String uri = open("Counter.axl", COUNTER);
        List<String> tokens = semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("2:0:4:keyword", "4:0:5:keyword", "15:43:5:keyword")), tokens.toString());
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
    static List<String> semanticTokens(Document document) {
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
            publications.incrementAndGet();
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
            logged.add(params.getMessage());
        }
    }
}

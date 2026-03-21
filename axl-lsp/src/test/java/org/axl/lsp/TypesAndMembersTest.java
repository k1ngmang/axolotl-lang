package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor features on members of data classes, classes, enums and interfaces, and on odd requests. */
class TypesAndMembersTest {

    static final String SHAPES = """
            package app

            sealed interface Shape {
                val name: String
            }

            data class Circle(val name: String, val radius: Double) : Shape
            data class Square(val name: String, val side: Double, val label: String?) : Shape

            enum Color { RED, GREEN, BLUE, }

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> 3.14 * shape.radius() * shape.radius()
                is Square -> shape.side() * shape.side()
            }

            fn describe(shape: Shape) -> String {
                if (shape !is Circle) {
                    return "not round"
                }
                return "radius " + shape.radius()
            }

            fn label(shape: Shape) -> String {
                if (shape is Square && shape.label() != null) {
                    return shape.label()!!
                }
                return "x"
            }

            fn make() -> Shape = Circle("c", 1.0)

            fn main() {
                val c = Circle("c", 2.0)
                println(c.radius())
                val color = Color.RED
                val k = when (val e = make()) {
                    is Circle -> e.name()
                    else -> "other"
                }
                val xs = java.util.List.of(1, 2)
                for (x in xs) {
                    println(x)
                }
                println(k + color.name())
            }
            """;

    static final String COUNTER = """
            package app
            import java.util.ArrayList

            class Counter(val name: String, var step: Int) {
                var count: Int = 0
                val history: ArrayList<Int> = ArrayList<Int>()

                fn next() -> Int {
                    count += step
                    history.add(count)
                    return count
                }
            }

            fn start(name: String) -> Counter = Counter(name, 1)

            fn main() {
                val c = Counter("c", 1)
                println(c.next())
                c.step = 5
                val d = start("d")
                println(d.count)
            }
            """;

    static final String MAIN = """
            package app
            import app.Shapes.Circle
            import app.Shapes.Color
            import app.Shapes.Shape

            fn main() {
                val c = Circle("m", 3.0)
                println(c.radius())
                val counter = Counter.start("x")
                println(counter.next())
                val shape: Shape = c
                val color = Color.GREEN
                println(color.ordinal() + Shapes.area(shape))
                val k = Counter("k", 2)
                println(k.count)
            }
            """;

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
    void answersNothingForPositionsOutsideTheText() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        assertNull(new Hovers(workspace).hover(uri, 3, -5));
        assertNull(new Hovers(workspace).hover(uri, -1, 0));
        assertNull(new Hovers(workspace).hover(uri, 9999, 9999));
        assertEquals(List.of(), new Completions(workspace).complete(uri, 3, -5));
        assertEquals(List.of(), new Completions(workspace).complete(uri, -2, 0));
        assertEquals(List.of(), new Definitions(workspace).definition(uri, 3, -5));
        assertEquals(List.of(), new Definitions(workspace).definition(uri, -1, -1));
    }

    @Test
    void cutsTheGeneratedJavaOfALongLine() throws Exception {
        String uri = open("Big.axl", "package app\n\nfn t(n: Int) -> Int = when (n) {\n    1 -> 1"
                + " + 1".repeat(5000) + "\n    else -> 2\n}\n");
        String hover = hover(uri, 3, 9000);
        assertTrue(hover.length() < 1000, hover.length() + " characters");
        assertContains(hover, "return 1 + 1");
        assertTrue(WorkspaceTest.semanticTokens(workspace.document(uri)).contains("3:20009:1:number"));
    }

    private static final String MORE = """

            fn side(o: Shape) -> Double = if (o is Square) o.side() else 0.0

            fn twice(n: Int) -> String = when (val m = n * 2) {
                2 -> "two" + m
                else -> "m" + m
            }

            fn first(s: Square) -> String? = s.label()

            fn useFirst(s: Square) -> Int {
                val f = first(s)
                return 1
            }
            """;

    @Test
    void narrowsAfterAnIfThatLeavesRightOfAndAndInIfValues() throws Exception {
        String text = SHAPES + MORE;
        String uri = open("Shapes.axl", text);
        assertContains(hoverAt(uri, text, "\"radius \" + |shape.radius()"), "shape: Circle");
        assertTrue(membersAt(uri, text, "\"radius \" + shape.|radius()").contains("radius"));
        assertContains(hoverAt(uri, text, "&& |shape.label()"), "shape: Square");
        assertTrue(membersAt(uri, text, "&& shape.|label()").contains("label"));
        assertContains(hoverAt(uri, text, "return |shape.label()!!"), "shape: Square");
        assertContains(hoverAt(uri, text, "|o.side() else"), "o: Square");
        assertTrue(membersAt(uri, text, "o.|side() else").contains("side"));
        assertContains(hoverAt(uri, text, "return |\"x\""), "Generated Java");
    }

    @Test
    void typesWhenBindingsAndLoopVariablesInAxolotlNames() throws Exception {
        String text = SHAPES + MORE;
        String uri = open("Shapes.axl", text);
        assertContains(hoverAt(uri, text, "when (val |m = n * 2)"), "val m: Int");
        assertContains(hoverAt(uri, text, "\"two\" + |m"), "val m: Int");
        assertContains(hoverAt(uri, text, "for (|x in xs)"), "val x: Int");
        assertEquals(List.of("Shapes.axl:41:9"), definitionsAt(uri, text, "for (|x in xs)"));
        assertContains(hoverAt(uri, text, "val |xs = "), "val xs: List<Int>");
        assertContains(hoverAt(uri, text, "val |c = Circle"), "val c: Circle");
        assertContains(hoverAt(uri, text, "val |f = first(s)"), "val f: String?");
    }

    @Test
    void completesMembersOfTheFileClassInItsOwnFile() throws Exception {
        assertMembers("Counter.axl", COUNTER.replace("println(d.count)", "println(d.co)"), "println(d.co|)", "count");
        assertMembers("Counter.axl", COUNTER.replace("println(d.count)", "println(Counter.st)"), "Counter.st|", "start");
        assertMembers("Counter.axl", COUNTER.replace("println(d.count)", "val z: Counter = c\n    z.ne"), "z.ne|", "next");
        assertMembers("Counter.axl", COUNTER.replace("println(d.count)", "start(\"q\").ne"), "start(\"q\").ne|", "next");
    }

    @Test
    void completesMembersAfterAConstructorCallAndThis() throws Exception {
        assertMembers("Shapes.axl", SHAPES.replace("println(c.radius())", "println(Circle(\"q\", 1.0).ra)"), "1.0).ra|", "radius");
        assertMembers("Shapes.axl", SHAPES.replace("println(c.radius())", "println(StringBuilder(\"x\").rev)"), "(\"x\").rev|", "reverse");
        String counter = COUNTER.replace("        return count\n", "        return this.co\n");
        assertMembers("Counter.axl", counter, "this.co|", "count");
    }

    @Test
    void offersTheKeywordsThatFitThePlace() throws Exception {
        String text = SHAPES.replace("val color = Color.RED", "val w = \n    val color = Color.RED");
        String uri = open("Shapes.axl", text);
        List<String> expression = labelsAt(uri, text, "val w = |");
        assertTrue(expression.containsAll(List.of("if", "when", "null")), expression.toString());
        assertTrue(expression.stream().noneMatch(List.of("package", "fn", "class", "else", "return")::contains), expression.toString());
        List<String> topLevel = labelsAt(uri, text, "|fn make()");
        assertTrue(topLevel.containsAll(List.of("fn", "class", "enum")), topLevel.toString());
        assertTrue(topLevel.stream().noneMatch(List.of("return", "while", "else")::contains), topLevel.toString());
        List<String> statement = labelsAt(uri, text, "|    val xs");
        assertTrue(statement.containsAll(List.of("val", "return", "for")) && !statement.contains("package"), statement.toString());
    }

    @Test
    void listsEnumMembersOnce() throws Exception {
        String text = SHAPES.replace("val color = Color.RED", "val color = Color.R");
        String uri = open("Shapes.axl", text);
        List<String> members = membersAt(uri, text, "Color.R|");
        assertTrue(members.contains("RED") && !members.contains("EnumDesc"), members.toString());
        assertEquals(1, members.stream().filter("valueOf"::equals).count(), members.toString());
    }

    @Test
    void goesToMembersOfAxolotlTypes() throws Exception {
        String shapes = open("Shapes.axl", SHAPES);
        assertEquals(List.of("Shapes.axl:6:40"), definitionsAt(shapes, SHAPES, "println(c.|radius())"));
        assertEquals(List.of("Shapes.axl:7:40"), definitionsAt(shapes, SHAPES, "is Square -> shape.|side()"));
        assertEquals(List.of("Shapes.axl:9:13"), definitionsAt(shapes, SHAPES, "Color.|RED"));
        assertEquals(List.of("Shapes.axl:6:22"), definitionsAt(shapes, SHAPES, "e.|name()"));
        String counter = open("Counter.axl", COUNTER);
        assertEquals(List.of("Counter.axl:7:7"), definitionsAt(counter, COUNTER, "println(c.|next())"));
        assertEquals(List.of("Counter.axl:3:36"), definitionsAt(counter, COUNTER, "c.|step = 5"));
        assertEquals(List.of("Counter.axl:4:8"), definitionsAt(counter, COUNTER, "println(d.|count)"));
    }

    @Test
    void goesToTypesAndMembersOfOtherFiles() throws Exception {
        open("Shapes.axl", SHAPES);
        open("Counter.axl", COUNTER);
        String main = open("Main.axl", MAIN);
        assertEquals(List.of("Shapes.axl:6:11"), definitionsAt(main, MAIN, "import app.Shapes.|Circle"));
        assertEquals(List.of("Shapes.axl:6:11"), definitionsAt(main, MAIN, "= |Circle(\"m\""));
        assertEquals(List.of("Shapes.axl:2:17"), definitionsAt(main, MAIN, "val shape: |Shape"));
        assertEquals(List.of("Shapes.axl:9:18"), definitionsAt(main, MAIN, "Color.|GREEN"));
        assertEquals(List.of("Shapes.axl:6:40"), definitionsAt(main, MAIN, "c.|radius()"));
        assertEquals(List.of("Counter.axl:7:7"), definitionsAt(main, MAIN, "counter.|next()"));
        assertEquals(List.of("Counter.axl:4:8"), definitionsAt(main, MAIN, "k.|count"));
        assertEquals(List.of("Counter.axl:3:6"), definitionsAt(main, MAIN, "= |Counter.start"));
    }

    @Test
    void hoversMembersAndDeclarationsInsideTypes() throws Exception {
        String shapes = open("Shapes.axl", SHAPES);
        String counter = open("Counter.axl", COUNTER);
        String main = open("Main.axl", MAIN);
        assertContains(hoverAt(counter, COUNTER, "c.|step = 5"), "var step: Int");
        assertContains(hoverAt(counter, COUNTER, "println(d.|count)"), "var count: Int");
        assertContains(hoverAt(counter, COUNTER, "println(c.|next())"), "fn next() -> Int");
        assertContains(hoverAt(shapes, SHAPES, "val |radius: Double"), "val radius: Double");
        assertContains(hoverAt(shapes, SHAPES, "    val |name: String"), "val name: String");
        assertContains(hoverAt(shapes, SHAPES, "{ |RED"), "RED: Color");
        assertContains(hoverAt(shapes, SHAPES, "Color.|RED"), "RED: Color");
        assertContains(hoverAt(main, MAIN, "= |Counter.start"), "class Counter(val name: String, var step: Int)");
        assertContains(hoverAt(main, MAIN, "|Shapes.area"), "class Shapes");
    }

    @Test
    void outlinesFieldsNamedLikeLettersOfVal() throws Exception {
        String uri = open("P.axl", "package app\n\ndata class P(val a: Int, val l: Int, val v: String)\n\ninterface I { val a: Int }\n");
        List<DocumentSymbol> symbols = new Outline(workspace).symbols(uri);
        assertEquals(List.of(17, 29, 41), symbols.getFirst().getChildren().stream()
                .map(f -> f.getSelectionRange().getStart().getCharacter()).toList());
        assertEquals(18, symbols.get(1).getChildren().getFirst().getSelectionRange().getStart().getCharacter());
    }

    @Test
    void publishesEachDiagnosticOnce() throws Exception {
        String uri = open("Twice.axl", "package app\n\nfn f(n: Int) = when (n) {\n    1, 2 -> 1; 3 -> 2\n"
                + "    else -> throw IllegalStateException(\"x\")\n}\n");
        List<Diagnostic> diagnostics = published.get(uri);
        assertTrue(!diagnostics.isEmpty());
        assertEquals(diagnostics.size(), diagnostics.stream().distinct().count(), diagnostics.toString());
    }

    private void assertMembers(String file, String text, String snippet, String member) throws Exception {
        String uri = open(file, text);
        List<String> members = membersAt(uri, text, snippet);
        assertTrue(members.contains(member), snippet + " " + members);
    }

    private List<String> labelsAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return new Completions(workspace).complete(uri, position[0], position[1]).stream().map(i -> i.getLabel()).toList();
    }

    /** The line and UTF-16 column of the `|` in a snippet of a text. */
    private static int[] at(String text, String snippet) {
        int offset = text.indexOf(snippet.replace("|", ""));
        assertTrue(offset >= 0, snippet);
        offset += snippet.indexOf('|');
        int line = (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
        return new int[] {line, offset - text.lastIndexOf('\n', offset - 1) - 1};
    }

    private String hoverAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return hover(uri, position[0], position[1]);
    }

    private List<String> membersAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return memberNames(uri, position[0], position[1]);
    }

    private List<String> definitionsAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return definitions(uri, position[0], position[1]);
    }

    private String hover(String uri, int line, int character) {
        Hover hover = new Hovers(workspace).hover(uri, line, character);
        return hover == null ? "" : hover.getContents().getRight().getValue();
    }

    private List<String> memberNames(String uri, int line, int character) {
        return new Completions(workspace).complete(uri, line, character).stream().map(i -> i.getInsertText()).toList();
    }

    private List<String> definitions(String uri, int line, int character) {
        return new Definitions(workspace).definition(uri, line, character).stream()
                .map(l -> Path.of(java.net.URI.create(l.getUri())).getFileName() + ":" + l.getRange().getStart().getLine()
                        + ":" + l.getRange().getStart().getCharacter()).toList();
    }

    private static void assertContains(String text, String part) {
        assertTrue(text.contains(part), text);
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

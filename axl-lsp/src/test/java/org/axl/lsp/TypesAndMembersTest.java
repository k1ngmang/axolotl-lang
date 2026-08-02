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
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolKind;
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

    static final String NOTES = """
            package app

            /** What a store of notes gives. */
            interface Notes {
                val name: String
                fn add(text: String) -> Int
                fn find(id: Int) -> String?
                fn clear()
            }

            class MemoryNotes(val name: String) : Notes {
                fn add(text: String) -> Int = 1

                fn find(id: Int) -> String? = null

                fn clear() {
                }
            }

            fn describe(notes: Notes) -> String {
                val id = notes.add("buy milk")
                notes.clear()
                return notes.name() + " " + id + " " + (notes.find(id) ?: "nothing")
            }

            fn text() -> String = "text"
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

    /** An enum with fields, classes extending Java classes, a data class with methods, `use`, `break` and `continue`. */
    static final String EXTENDED = """
            package app

            enum Planet(val mass: Double, val radius: Double) {
                MERCURY(3.303e23, 2.4397e6),
                EARTH(5.976e24, 6.37814e6)
            }

            class NotFound(val id: String) : RuntimeException("not found: " + id)

            class Loud(val times: Int) : Thread("loud") {
                var shouted = 0

                fn run() {
                    shouted += times
                }
            }

            data class Money(val cents: Long) : Comparable<Money> {
                fn compareTo(other: Money) -> Int = Long.compare(cents, other.cents())

                fn plus(other: Money) -> Money = Money(cents + other.cents())
            }

            fn gravity(p: Planet) -> Double = 6.67300E-11 * p.mass / (p.radius * p.radius)

            fn main() {
                println(Planet.EARTH.mass)
                val loud = Loud(3)
                loud.start()
                println(loud.shouted + " " + loud.getName())
                val missing = NotFound("7")
                println(missing.getMessage() + missing.id)
                println(Money(250).plus(Money(99)).cents())
                use (val reader = java.io.StringReader("x")) {
                    println(reader.read())
                } else e -> println(e.getMessage())
                for (p in Planet.values()) {
                    if (p.mass > 1.0) {
                        break
                    }
                    continue
                }
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
    void offersAnnotationTypesAfterAnAt() throws Exception {
        String text = SHAPES.replace("package app\n", "package app\n\nimport java.lang.annotation.Documented\nimport java.util.List\n")
                .replace("fn make()", "@\nfn make()");
        String uri = open("Shapes.axl", text);
        List<String> found = labelsAt(uri, text, "@|\nfn make()");
        assertTrue(found.containsAll(List.of("Deprecated", "Override", "FunctionalInterface", "Documented")), found.toString());
        assertTrue(found.stream().noneMatch(List.of("List", "fn", "Circle", "println")::contains), found.toString());
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

    @Test
    void colorsEnumConstantsAsEnumMembers() throws Exception {
        String uri = open("Shapes.axl", SHAPES);
        List<String> tokens = WorkspaceTest.semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("9:13:3:enumMember", "9:18:5:enumMember", "35:22:3:enumMember")), tokens.toString());
        assertTrue(tokens.contains("9:5:5:type"), tokens.toString());
    }

    @Test
    void listsTheMembersOfAnInterfaceInItsHover() throws Exception {
        String uri = open("Notes.axl", NOTES);
        String hover = hoverAt(uri, NOTES, "interface |Notes {");
        assertContains(hover, "interface Notes {\n    val name: String\n    fn add(text: String) -> Int\n"
                + "    fn find(id: Int) -> String?\n    fn clear()\n}");
        assertContains(hoverAt(uri, NOTES, "fn describe(notes: |Notes)"), "fn clear()");
        assertContains(hoverAt(uri, NOTES, "class MemoryNotes(val name: String) : |Notes"), "fn add(text: String) -> Int");
    }

    @Test
    void hoversAndGoesToTheMethodsOfAnInterface() throws Exception {
        String uri = open("Notes.axl", NOTES);
        assertContains(hoverAt(uri, NOTES, "notes.|add(\"buy milk\")"), "fn add(text: String) -> Int");
        assertContains(hoverAt(uri, NOTES, "(notes.|find(id)"), "fn find(id: Int) -> String?");
        assertContains(hoverAt(uri, NOTES, "notes.|clear()"), "fn clear()");
        assertContains(hoverAt(uri, NOTES, "    fn |find(id: Int) -> String?\n    fn clear()"), "fn find(id: Int) -> String?");
        assertEquals(List.of("Notes.axl:5:7"), definitionsAt(uri, NOTES, "notes.|add(\"buy milk\")"));
        assertEquals(List.of("Notes.axl:7:7"), definitionsAt(uri, NOTES, "notes.|clear()"));
        assertEquals(List.of("Notes.axl:4:8"), definitionsAt(uri, NOTES, "return notes.|name()"));
    }

    @Test
    void completesTheMethodsOfAnInterfaceTypedValueWithTheirSignatures() throws Exception {
        String text = NOTES.replace("notes.clear()", "notes.cl");
        String uri = open("Notes.axl", text);
        int[] position = at(text, "    notes.cl|\n");
        List<org.eclipse.lsp4j.CompletionItem> items = new Completions(workspace).complete(uri, position[0], position[1]);
        assertTrue(items.stream().map(i -> i.getInsertText()).toList().containsAll(List.of("add", "find", "clear", "name")),
                items.toString());
        org.eclipse.lsp4j.CompletionItem add = items.stream().filter(i -> "add".equals(i.getInsertText())).findFirst().orElseThrow();
        assertEquals("add(String text)", add.getLabel());
        assertEquals("Int", add.getDetail());
        assertEquals(org.eclipse.lsp4j.CompletionItemKind.Method, add.getKind());
        org.eclipse.lsp4j.CompletionItem find = items.stream().filter(i -> "find".equals(i.getInsertText())).findFirst().orElseThrow();
        assertEquals("find(int id)", find.getLabel());
        assertEquals("String?", find.getDetail());
    }

    @Test
    void colorsTheNamesOfInterfaceMethodsAsMethodsAndKeepsTheirParametersToThemselves() throws Exception {
        String uri = open("Notes.axl", NOTES);
        List<String> tokens = WorkspaceTest.semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("5:7:3:method", "6:7:4:method", "7:7:5:method")), tokens.toString());
        assertTrue(tokens.containsAll(List.of("5:11:4:parameter", "11:7:3:function", "13:7:4:function")), tokens.toString());
        assertTrue(SemanticTokensProvider.TYPES.contains("method"));

        String text = "package app\n\ninterface Sink {\n    fn put(text: String)\n}\n\nfn show(s: Sink) -> String {\n"
                + "    val text = \"x\"\n    return text\n}\n";
        tokens = WorkspaceTest.semanticTokens(workspace.document(open("Sink.axl", text)));
        assertTrue(tokens.containsAll(List.of("3:7:3:method", "3:11:4:parameter", "7:8:4:variable", "8:11:4:variable")), tokens.toString());
    }

    @Test
    void readsMethodsOfInterfacesWithoutThrowingWhereverTheCursorIs() throws Exception {
        String uri = open("Notes.axl", NOTES);
        for (int line = 0; line < 9; line++) {
            for (int character = 0; character < 40; character++) {
                new Hovers(workspace).hover(uri, line, character);
                new Definitions(workspace).definition(uri, line, character);
                new Completions(workspace).complete(uri, line, character);
            }
        }
        assertEquals(5, new Outline(workspace).symbols(uri).getFirst().getChildren().size() + 1);
    }

    @Test
    void hoversEnumFieldsSuperclassArgumentsDataClassMethodsAndTheLocalsOfUse() throws Exception {
        String uri = open("Extended.axl", EXTENDED);
        assertEquals(List.of(), published.get(uri).stream().map(Diagnostic::getMessage).toList());
        assertContains(hoverAt(uri, EXTENDED, "fn gravity(p: |Planet)"), "enum Planet(val mass: Double, val radius: Double)");
        assertContains(hoverAt(uri, EXTENDED, "val missing = |NotFound(\"7\")"), "class NotFound(val id: String) : RuntimeException(...)");
        assertContains(hoverAt(uri, EXTENDED, "val loud = |Loud(3)"), "class Loud(val times: Int) : Thread(...)");
        assertContains(hoverAt(uri, EXTENDED, "println(Money(250).|plus("), "fn plus(other: Money) -> Money");
        assertContains(hoverAt(uri, EXTENDED, "data class |Money("), "data class Money(val cents: Long) : Comparable<Money> {\n"
                + "    fn compareTo(other: Money) -> Int\n    fn plus(other: Money) -> Money\n}");
        assertContains(hoverAt(uri, EXTENDED, "println(Planet.EARTH.|mass)"), "val mass: Double");
        assertContains(hoverAt(uri, EXTENDED, "* p.|mass /"), "val mass: Double");
        assertContains(hoverAt(uri, EXTENDED, "println(Planet.|EARTH.mass)"), "EARTH: Planet");
        assertContains(hoverAt(uri, EXTENDED, "Long.compare(|cents, other"), "val cents: Long");
        assertContains(hoverAt(uri, EXTENDED, "println(|reader.read())"), "val reader: StringReader");
        assertContains(hoverAt(uri, EXTENDED, "println(|e.getMessage())"), "e: Exception");
        assertContains(hoverAt(uri, EXTENDED, "if (|p.mass > 1.0)"), "val p: Planet");
    }

    @Test
    void goesToEnumFieldsDataClassMethodsAndTheLocalsOfUse() throws Exception {
        String uri = open("Extended.axl", EXTENDED);
        assertEquals(List.of(location(EXTENDED, "enum Planet(val |mass")), definitionsAt(uri, EXTENDED, "println(Planet.EARTH.|mass)"));
        assertEquals(List.of(location(EXTENDED, "val |radius: Double")), definitionsAt(uri, EXTENDED, "(p.|radius *"));
        assertEquals(List.of(location(EXTENDED, "    |EARTH(5.976e24")), definitionsAt(uri, EXTENDED, "println(Planet.|EARTH.mass)"));
        assertEquals(List.of(location(EXTENDED, "fn |plus(other")), definitionsAt(uri, EXTENDED, "println(Money(250).|plus("));
        assertEquals(List.of(location(EXTENDED, "data class Money(val |cents")), definitionsAt(uri, EXTENDED, "Long.compare(|cents,"));
        assertEquals(List.of(location(EXTENDED, "use (val |reader")), definitionsAt(uri, EXTENDED, "println(|reader.read())"));
        assertEquals(List.of(location(EXTENDED, "} else |e ->")), definitionsAt(uri, EXTENDED, "println(|e.getMessage())"));
        assertEquals(List.of(location(EXTENDED, "for (|p in Planet")), definitionsAt(uri, EXTENDED, "if (|p.mass > 1.0)"));
    }

    @Test
    void completesEnumFieldsInheritedMembersAndTheResourceOfUse() throws Exception {
        String text = EXTENDED.replace("println(Planet.EARTH.mass)", "println(Planet.EARTH.ma)").replace("loud.start()", "loud.st")
                .replace("println(reader.read())", "println(reader.re)").replace("else e -> println(e.getMessage())", "else e -> println(e.get)")
                .replace("fn plus(other: Money) -> Money = Money(cents + other.cents())", "fn plus(other: Money) -> Money = Money(ce)");
        String uri = open("Extended.axl", text);
        List<String> fields = membersAt(uri, text, "Planet.EARTH.ma|");
        assertTrue(fields.containsAll(List.of("mass", "radius", "ordinal")), fields.toString());
        List<String> inherited = membersAt(uri, text, "    loud.st|");
        assertTrue(inherited.containsAll(List.of("start", "shouted", "getName")), inherited.toString());
        assertTrue(membersAt(uri, text, "reader.re|").contains("read"), membersAt(uri, text, "reader.re|").toString());
        assertTrue(membersAt(uri, text, "println(e.get|)").contains("getMessage"), membersAt(uri, text, "println(e.get|)").toString());
        List<String> inBody = labelsAt(uri, text, "Money(ce|)");
        assertTrue(inBody.containsAll(List.of("cents", "compareTo", "plus")), inBody.toString());
    }

    @Test
    void narrowsAfterAnIfThatLeavesTheLoop() throws Exception {
        String text = SHAPES + """

                fn radii(shapes: java.util.List<Shape>) -> Double {
                    var total = 0.0
                    for (shape in shapes) {
                        if (shape !is Circle) {
                            continue
                        }
                        total += shape.radius()
                    }
                    return total
                }
                """;
        String uri = open("Shapes.axl", text);
        assertContains(hoverAt(uri, text, "total += |shape.radius()"), "val shape: Circle");
        assertEquals(List.of("Shapes.axl:6:40"), definitionsAt(uri, text, "total += shape.|radius()"));
    }

    @Test
    void outlinesEnumFieldsAndDataClassMethods() throws Exception {
        String uri = open("Extended.axl", EXTENDED);
        List<DocumentSymbol> symbols = new Outline(workspace).symbols(uri);
        assertEquals(List.of("Planet", "NotFound", "Loud", "Money", "gravity", "main"), symbols.stream().map(DocumentSymbol::getName).toList());
        DocumentSymbol planet = symbols.getFirst();
        assertEquals(List.of("mass", "radius", "MERCURY", "EARTH"), planet.getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(List.of(SymbolKind.Field, SymbolKind.Field, SymbolKind.EnumMember, SymbolKind.EnumMember),
                planet.getChildren().stream().map(DocumentSymbol::getKind).toList());
        assertEquals(new Position(2, 16), planet.getChildren().getFirst().getSelectionRange().getStart());
        DocumentSymbol money = symbols.get(3);
        assertEquals(List.of("cents", "compareTo", "plus"), money.getChildren().stream().map(DocumentSymbol::getName).toList());
        assertEquals(List.of(SymbolKind.Field, SymbolKind.Method, SymbolKind.Method),
                money.getChildren().stream().map(DocumentSymbol::getKind).toList());
        List<String> lines = EXTENDED.lines().toList();
        assertEquals(new Range(new Position(18, 4), new Position(18, lines.get(18).length())), money.getChildren().get(1).getRange());
        assertEquals(new Range(new Position(20, 4), new Position(20, lines.get(20).length())), money.getChildren().get(2).getRange());
        assertEquals(new Position(20, 7), money.getChildren().get(2).getSelectionRange().getStart());
    }

    @Test
    void completesUseBreakContinueAndTheConsoleFunctions() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n\n}\n");
        List<String> labels = labelsAt(uri, "package app\n\nfn main() {\n\n}\n", "fn main() {\n|\n}");
        assertTrue(labels.containsAll(List.of("use", "break", "continue", "print", "readLine", "println")), labels.toString());
    }

    private static String location(String text, String snippet) {
        int[] position = at(text, snippet);
        return "Extended.axl:" + position[0] + ":" + position[1];
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

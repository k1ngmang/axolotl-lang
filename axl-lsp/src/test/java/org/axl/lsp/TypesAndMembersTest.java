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

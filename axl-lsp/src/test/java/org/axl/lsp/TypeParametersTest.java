package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor features on data classes, classes, interfaces and functions with type parameters. */
class TypeParametersTest {

    static final String GENERICS = """
            package app

            import java.util.ArrayList
            import java.util.List

            data class Pair<A, B>(val first: A, val second: B)

            interface Repository<K, V> {
                fn find(key: K) -> V?
                fn save(key: K, value: V)
            }

            class Stack<T : Object> {
                val items: List<T> = ArrayList<T>()

                fn push(item: T) {
                    items.add(item)
                }

                fn pop() -> T? = if (items.isEmpty()) null else items.remove(items.size() - 1)
            }

            fn firstOrNull<T>(xs: List<T>) -> T? = if (xs.isEmpty()) null else xs.get(0)

            fn largest<T : Comparable<T>>(xs: List<T>) -> T? {
                var best: T? = null
                for (x in xs) {
                    if (best == null || x.compareTo(best) > 0) {
                        best = x
                    }
                }
                return best
            }

            fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A> = Pair<B, A>(p.second(), p.first())

            fn main() {
                val pair = Pair<String, Int>("age", 41)
                println(pair.first().length())
                val stack = Stack<String>()
                stack.push("a")
                println(stack.pop())
                println(swap(pair).second())
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
    void hoversDeclarationsWithTheirTypeParameters() throws Exception {
        String uri = open("Generics.axl", GENERICS);
        assertContains(hoverAt(uri, GENERICS, "data class |Pair<A, B>"), "data class Pair<A, B>(val first: A, val second: B)");
        assertContains(hoverAt(uri, GENERICS, "interface |Repository<K, V>"),
                "interface Repository<K, V> {\n    fn find(key: K) -> V?\n    fn save(key: K, value: V)\n}");
        assertContains(hoverAt(uri, GENERICS, "class |Stack<T : Object>"), "class Stack<T : Object>");
        assertContains(hoverAt(uri, GENERICS, "fn |largest<T"), "fn largest<T : Comparable<T>>(xs: List<T>) -> T?");
        assertContains(hoverAt(uri, GENERICS, "fn |swap<A, B>"), "fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A>");
        assertContains(hoverAt(uri, GENERICS, "println(|swap(pair)"), "fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A>");
        assertContains(hoverAt(uri, GENERICS, "fn |find(key: K)"), "fn find(key: K) -> V?");
    }

    @Test
    void hoversAndGoesToTypeParameters() throws Exception {
        String uri = open("Generics.axl", GENERICS);
        assertContains(hoverAt(uri, GENERICS, "xs: List<|T>) -> T? {"), "fn largest<T : Comparable<T>>");
        assertContains(hoverAt(uri, GENERICS, "val first: |A"), "data class Pair<A, B>");
        assertContains(hoverAt(uri, GENERICS, "fn push(item: |T)"), "class Stack<T : Object>");
        assertContains(hoverAt(uri, GENERICS, "fn find(key: |K)"), "interface Repository<K, V>");
        assertEquals(List.of("Generics.axl:24:11"), definitionsAt(uri, GENERICS, "xs: List<|T>) -> T? {"));
        assertEquals(List.of("Generics.axl:24:11"), definitionsAt(uri, GENERICS, "var best: |T? = null"));
        assertEquals(List.of("Generics.axl:5:16"), definitionsAt(uri, GENERICS, "val first: |A"));
        assertEquals(List.of("Generics.axl:34:8"), definitionsAt(uri, GENERICS, "= Pair<B, |A>(p.second()"));
        assertEquals(List.of("Generics.axl:12:12"), definitionsAt(uri, GENERICS, "fn pop() -> |T?"));
    }

    @Test
    void completesTypeParametersWhereATypeIsWritten() throws Exception {
        String uri = open("Generics.axl", GENERICS);
        List<CompletionItem> items = itemsAt(uri, GENERICS, "var best: |T? = null");
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("T", "Pair", "Stack", "String")), labels.toString());
        assertTrue(labels.stream().noneMatch(List.of("best", "xs", "largest")::contains), labels.toString());
        CompletionItem t = items.stream().filter(i -> i.getLabel().equals("T")).findFirst().orElseThrow();
        assertEquals(CompletionItemKind.TypeParameter, t.getKind());
        assertEquals("Comparable<T>", t.getDetail());
        labels = itemsAt(uri, GENERICS, "fn push(item: |T)").stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("T", "Pair")) && !labels.contains("items"), labels.toString());
        labels = itemsAt(uri, GENERICS, "val pair = |Pair<String, Int>").stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.contains("Pair") && !labels.contains("T"), labels.toString());
    }

    @Test
    void completesTheMembersOfValuesOfTypeParameterTypes() throws Exception {
        assertMembers(GENERICS.replace("x.compareTo(best) > 0", "x.co"), "x.co|", "compareTo");
        assertMembers(GENERICS.replace("items.add(item)", "item.to"), "item.to|", "toString");
        assertMembers(GENERICS.replace("println(pair.first().length())", "pair.first().le"), "pair.first().le|", "length");
        assertMembers(GENERICS.replace("println(stack.pop())", "stack.pop().le"), "stack.pop().le|", "length");
    }

    @Test
    void detailsAGenericFunctionWithItsSignature() throws Exception {
        String uri = open("Generics.axl", GENERICS);
        List<CompletionItem> items = itemsAt(uri, GENERICS, "    |println(pair.first().length())");
        CompletionItem largest = items.stream().filter(i -> i.getLabel().equals("largest")).findFirst().orElseThrow();
        assertEquals("fn largest<T : Comparable<T>>(xs: List<T>) -> T?", largest.getDetail());
        CompletionItem firstOrNull = items.stream().filter(i -> i.getLabel().equals("firstOrNull")).findFirst().orElseThrow();
        assertEquals("fn firstOrNull<T>(xs: List<T>) -> T?", firstOrNull.getDetail());
    }

    @Test
    void colorsTypeParametersWhereTheyAreDeclaredAndUsed() throws Exception {
        String uri = open("Colors.axl", """
                package app

                import java.util.List

                data class Pair<A, B>(val first: A, val second: B)

                fn firstOrNull<T>(xs: List<T>) -> T? = if (xs.isEmpty()) null else xs.get(0)

                class Stack<T : Object> {
                    fn push(item: T) {
                    }
                }

                interface Repository<K, V> {
                    fn find(key: K) -> V?
                }
                """);
        List<String> tokens = WorkspaceTest.semanticTokens(workspace.document(uri));
        assertTrue(tokens.containsAll(List.of("4:16:1:typeParameter", "4:19:1:typeParameter", "4:33:1:typeParameter",
                "4:48:1:typeParameter", "6:15:1:typeParameter", "6:27:1:typeParameter", "6:34:1:typeParameter",
                "8:12:1:typeParameter", "9:18:1:typeParameter", "13:21:1:typeParameter", "13:24:1:typeParameter",
                "14:17:1:typeParameter", "14:23:1:typeParameter")), tokens.toString());
        assertTrue(tokens.containsAll(List.of("4:11:4:type", "8:6:5:type", "8:16:6:type", "13:10:10:type", "6:22:4:type")), tokens.toString());
        assertTrue(tokens.containsAll(List.of("6:18:2:parameter", "6:43:2:parameter", "9:12:4:parameter", "14:12:3:parameter")), tokens.toString());
        assertEquals(13, tokens.stream().filter(t -> t.endsWith(":typeParameter")).count(), tokens.toString());
        assertTrue(SemanticTokensProvider.TYPES.contains("typeParameter"));
    }

    @Test
    void translatesTheConformanceCasesWithoutDiagnostics() throws Exception {
        for (String name : List.of("type_parameters", "pipe_fallback")) {
            String text = Files.readString(Path.of("..", "tests", "conformance", name, "src", "app", "Main.axl"));
            String uri = open("Main.axl", text);
            assertEquals(List.of(), published.get(uri), name);
        }
    }

    @Test
    void readsGenericDeclarationsWithoutThrowingWhereverTheCursorIs() throws Exception {
        String uri = open("Generics.axl", GENERICS);
        for (int line : List.of(5, 7, 8, 12, 13, 15, 19, 22, 24, 25, 27, 34, 37, 42)) {
            for (int character = 0; character < 50; character++) {
                new Hovers(workspace).hover(uri, line, character);
                new Definitions(workspace).definition(uri, line, character);
                new Completions(workspace).complete(uri, line, character);
            }
        }
        assertEquals(7, new Outline(workspace).symbols(uri).size());
    }

    private void assertMembers(String text, String snippet, String member) throws Exception {
        String uri = open("Generics.axl", text);
        List<String> members = itemsAt(uri, text, snippet).stream().map(CompletionItem::getInsertText).toList();
        assertTrue(members.contains(member), snippet + " " + members);
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
        Hover hover = new Hovers(workspace).hover(uri, position[0], position[1]);
        return hover == null ? "" : hover.getContents().getRight().getValue();
    }

    private List<CompletionItem> itemsAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return new Completions(workspace).complete(uri, position[0], position[1]);
    }

    private List<String> definitionsAt(String uri, String text, String snippet) {
        int[] position = at(text, snippet);
        return new Definitions(workspace).definition(uri, position[0], position[1]).stream()
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

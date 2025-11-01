package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor features on a workspace, without the protocol around them. */
class WorkspaceTest {

    @TempDir
    Path project;

    private final Workspace workspace = new Workspace();

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(project.resolve("src/main/axl/app"));
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
    void findsTheNameRightAfterAParenthesis() throws Exception {
        String uri = open("Main.axl", "package app\n\nfn main() {\n    val count = 1\n    println(count)\n}\n");
        List<Location> found = new Definitions(workspace).definition(uri, 4, 12);
        assertEquals(List.of(new Position(3, 4)), found.stream().map(l -> l.getRange().getStart()).toList());
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
}

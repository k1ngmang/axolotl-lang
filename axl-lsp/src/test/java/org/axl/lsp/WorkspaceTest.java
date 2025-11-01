package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
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
}

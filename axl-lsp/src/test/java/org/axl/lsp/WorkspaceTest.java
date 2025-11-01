package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}

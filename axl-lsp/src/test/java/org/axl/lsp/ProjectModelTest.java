package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectModelTest {

    @Test
    void readsModulesFromAxlJson(@TempDir Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("core"));
        Files.createDirectories(module.resolve("build/axl"));
        Files.writeString(module.resolve("build/axl/axl.json"), """
                {"sourceRoots": ["src/main/axl"], "javaSourceRoots": ["src/main/java"], "classpath": ["/libs/a.jar"], "strictNull": true}
                """);
        Files.createDirectories(module.resolve("src/main/axl/core"));

        Module found = ProjectModel.discover(List.of(root)).moduleOf(module.resolve("src/main/axl/core/A.axl"), "core");

        assertEquals(module, found.root());
        assertEquals(List.of(module.resolve("src/main/axl")), found.axlRoots());
        assertEquals(List.of(module.resolve("src/main/java")), found.javaRoots());
        assertEquals(List.of(Path.of("/libs/a.jar")), found.classpath());
        assertTrue(found.strictNull());
    }

    @Test
    void guessesModulesFromTheDirectoryLayout(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/test/axl/app"));
        Files.createDirectories(root.resolve("src/test/java"));

        Module found = ProjectModel.discover(List.of(root)).moduleOf(root.resolve("src/test/axl/app/T.axl"), "app");

        assertEquals(List.of(root.resolve("src/test/axl")), found.axlRoots());
        assertEquals(List.of(root.resolve("src/test/java")), found.javaRoots());
    }

    @Test
    void derivesTheRootOfLooseFilesFromTheirPackage(@TempDir Path root) {
        Module found = ProjectModel.discover(List.of(root)).moduleOf(root.resolve("x/app/util/Main.axl"), "app.util");
        assertEquals(List.of(root.resolve("x")), found.axlRoots());
    }
}

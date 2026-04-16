package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
        Path jar = root.resolve("libs/a.jar").toAbsolutePath();
        Files.writeString(module.resolve("build/axl/axl.json"), """
                {"sourceRoots": ["src/main/axl"], "javaSourceRoots": ["src/main/java"], "classpath": ["%s"], "strictNull": true}
                """.formatted(jar.toString().replace("\\", "\\\\")));
        Files.createDirectories(module.resolve("src/main/axl/core"));

        Module found = ProjectModel.discover(List.of(root)).moduleOf(module.resolve("src/main/axl/core/A.axl"), "core");

        assertEquals(module, found.root());
        assertEquals(List.of(module.resolve("src/main/axl")), found.axlRoots());
        assertEquals(List.of(module.resolve("src/main/java")), found.javaRoots());
        assertEquals(List.of(jar), found.classpath());
        assertTrue(found.strictNull());
    }

    @Test
    void skipsDescriptionsThatCannotBeRead(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("c"));
        Files.writeString(root.resolve("c/axl.json"), "{\"sourceRoots\": [");
        Files.createDirectories(root.resolve("d/build/axl"));
        Files.writeString(root.resolve("d/build/axl/axl.json"), "{\"sourceRoots\": \"src/main/axl\"}");
        Files.createDirectories(root.resolve("e"));
        Files.writeString(root.resolve("e/axl.json"), "null");
        Files.createDirectories(root.resolve("good/src/main/axl"));
        Files.writeString(root.resolve("good/axl.json"), "{\"sourceRoots\": [\"src/main/axl\"]}");

        ProjectModel model = ProjectModel.discover(List.of(root));

        assertEquals(List.of(root.resolve("good/src/main/axl")), model.modules().stream().flatMap(m -> m.axlRoots().stream()).toList());
        assertEquals(3, model.problems().size(), model.problems().toString());
        assertTrue(model.problems().getFirst().contains("c" + File.separator + "axl.json"), model.problems().toString());
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
    void letsOtherSourceSetsOfTheLayoutSeeTheMainOne(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/integrationTest/axl"));
        Files.createDirectories(root.resolve("src/main/axl"));
        Files.createDirectories(root.resolve("src/main/java"));
        ProjectModel model = ProjectModel.discover(List.of(root));

        Module test = model.moduleOf(root.resolve("src/integrationTest/axl/T.axl"), "");
        Module main = model.moduleOf(root.resolve("src/main/axl/M.axl"), "");

        assertEquals(List.of(root.resolve("src/integrationTest/axl"), root.resolve("src/main/axl")), test.axlRoots());
        assertEquals(List.of(root.resolve("src/main/java")), test.javaRoots());
        assertEquals(List.of(root.resolve("src/main/axl")), main.axlRoots());
    }

    @Test
    void derivesTheRootOfLooseFilesFromTheirPackage(@TempDir Path root) {
        Module found = ProjectModel.discover(List.of(root)).moduleOf(root.resolve("x/app/util/Main.axl"), "app.util");
        assertEquals(List.of(root.resolve("x")), found.axlRoots());
    }

    @Test
    void takesTheFileSystemRootForALooseFileWhoseDirectoryIsItsPackage() {
        Path file = Path.of("/app/Main.axl").toAbsolutePath();
        Module found = ProjectModel.discover(List.of()).moduleOf(file, "app");
        assertEquals(List.of(file.getRoot()), found.axlRoots());
    }
}

package org.axl.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    private final List<String> messages = new ArrayList<>();

    private GenerateMojo mojo(Path base) {
        GenerateMojo mojo = new GenerateMojo();
        mojo.project = new MavenProject();
        mojo.sourceDirectory = base.resolve("src/main/axl").toFile();
        mojo.outputDirectory = base.resolve("target/generated-sources/axl").toFile();
        mojo.stateFile = base.resolve("target/axl-state.json").toFile();
        mojo.javaRelease = 21;
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                messages.add(content.toString());
            }
        });
        return mojo;
    }

    @Test
    void translatesOnlyChangedFiles(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a() -> Int = 1\n");
        Files.writeString(sources.resolve("B.axl"), "package app\n\nfn b() -> Int = 2\n");
        Path generated = base.resolve("target/generated-sources/axl/app");

        mojo(base).execute();
        assertEquals("Translated 2 of 2 .axl file(s)", messages.getLast());
        assertTrue(Files.exists(generated.resolve("A.java")));
        assertTrue(Files.exists(base.resolve("target/axl-state.json")));

        mojo(base).execute();
        assertEquals("Translated 0 of 2 .axl file(s)", messages.getLast());

        Files.writeString(sources.resolve("B.axl"), "package app\n\nfn b() -> Int = 3\n");
        Files.delete(sources.resolve("A.axl"));
        GenerateMojo mojo = mojo(base);
        mojo.execute();
        assertEquals("Translated 1 of 1 .axl file(s)", messages.getLast());
        assertTrue(Files.notExists(generated.resolve("A.java")));
        assertTrue(Files.readString(generated.resolve("B.java")).contains("return 3;"));
        assertEquals(List.of(base.resolve("target/generated-sources/axl").toString()), mojo.project.getCompileSourceRoots());
        assertTrue(mojo.project.getProperties().getProperty("axl.javacPlugin").startsWith("-Xplugin:AxlDiagnostics file:"));
        assertEquals("21", mojo.project.getProperties().getProperty("maven.compiler.release"));
    }
}

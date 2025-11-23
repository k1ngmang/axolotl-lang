package org.axl.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    private final List<String> messages = new ArrayList<>();

    private GenerateMojo mojo(Path base) {
        return configure(new GenerateMojo(), base);
    }

    private GenerateMojo configure(GenerateMojo mojo, Path base) {
        mojo.project = new MavenProject();
        mojo.project.getModel().setBuild(new Build());
        mojo.project.getBuild().setDirectory(base.resolve("target").toString());
        mojo.project.getBuild().setOutputDirectory(base.resolve("target/classes").toString());
        mojo.sourceDirectory = base.resolve("src/main/axl").toFile();
        mojo.outputDirectory = base.resolve("target/generated-sources/axl").toFile();
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
        assertTrue(Files.exists(base.resolve("target/axl/generated-sources_axl.state.json")));
        assertEquals(List.of("app"), fileNames(base.resolve("target/generated-sources/axl")));

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

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Test
    void retranslatesInferredValuesWhenAnotherFileChanges(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn make() -> String = \"x\"\n");
        Files.writeString(sources.resolve("B.axl"), "package app\n\nval v = A.make()\n");
        Path b = base.resolve("target/generated-sources/axl/app/B.java");
        mojo(base).execute();
        assertTrue(Files.readString(b).contains("String v"));

        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn make() -> Int = 1\n");
        mojo(base).execute();
        assertEquals("Translated 2 of 2 .axl file(s)", messages.getLast());
        assertTrue(Files.readString(b).contains("int v"));
    }

    @Test
    void ownClassesDoNotCountAsAClasspathChange(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a() -> Int = 1\n");
        mojo(base).execute();
        Files.write(Files.createDirectories(base.resolve("target/classes/app")).resolve("A.class"), new byte[] {1});
        mojo(base).execute();
        assertEquals("Translated 0 of 1 .axl file(s)", messages.getLast());
    }

    @Test
    void retranslatesWhenAClassInAClasspathDirectoryChanges(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a() -> Int = 1\n");
        Path lib = base.resolve("lib/target/classes");
        Path libClass = Files.createDirectories(lib.resolve("lib")).resolve("Lib.class");
        Files.write(libClass, new byte[] {1});
        configure(withClasspath(lib), base).execute();
        Files.write(libClass, new byte[] {1, 2});
        configure(withClasspath(lib), base).execute();
        assertEquals("Translated 1 of 1 .axl file(s)", messages.getLast());
    }

    private static GenerateMojo withClasspath(Path entry) {
        return new GenerateMojo() {
            @Override
            List<String> classpath() {
                return List.of(entry.toString());
            }
        };
    }

    @Test
    void deletesTheOutputsWhenTheSourceDirectoryIsGone(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a() -> Int = 1\n");
        mojo(base).execute();
        Files.delete(sources.resolve("A.axl"));
        Files.delete(sources);
        Files.delete(base.resolve("src/main/axl"));
        mojo(base).execute();
        assertTrue(Files.notExists(base.resolve("target/generated-sources/axl/app/A.java")));
        assertTrue(Files.notExists(base.resolve("target/generated-sources/axl/app/A.axlmap")));
        assertTrue(Files.notExists(base.resolve("target/axl/generated-sources_axl.state.json")));
    }

    @Test
    void retranslatesInferredValuesWhenAFileIsRemoved(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn make() -> String = \"x\"\n");
        Files.writeString(sources.resolve("B.axl"), "package app\n\nval v = A.make()\n");
        mojo(base).execute();
        Files.delete(sources.resolve("A.axl"));
        assertThrows(MojoFailureException.class, () -> mojo(base).execute());
        assertTrue(Files.notExists(base.resolve("target/generated-sources/axl/app/B.java")));
    }

    @Test
    void retranslatesEverythingWhenAnOptionChanges(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a(s: String?) {\n    val r = s ?: \"none\"\n}\n");
        mojo(base).execute();
        GenerateMojo inline = mojo(base);
        inline.inlineElvis = true;
        inline.execute();
        assertEquals("Translated 1 of 1 .axl file(s)", messages.getLast());
    }
}

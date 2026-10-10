package org.axl.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    private final List<String> messages = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private GenerateMojo mojo(Path base) {
        return configure(new GenerateMojo(), base);
    }

    private GenerateMojo configure(GenerateMojo mojo, Path base) {
        project(mojo, base);
        mojo.sourceDirectory = base.resolve("src/main/axl").toFile();
        mojo.outputDirectory = base.resolve("target/generated-sources/axl").toFile();
        return mojo;
    }

    private GenerateTestMojo testMojo(Path base) {
        GenerateTestMojo mojo = new GenerateTestMojo();
        project(mojo, base);
        mojo.project.getBuild().setTestOutputDirectory(base.resolve("target/test-classes").toString());
        mojo.sourceDirectory = base.resolve("src/main/axl").toFile();
        mojo.testSourceDirectory = base.resolve("src/test/axl").toFile();
        mojo.testOutputDirectory = base.resolve("target/generated-test-sources/axl").toFile();
        return mojo;
    }

    private void project(AbstractGenerateMojo mojo, Path base) {
        mojo.project = new MavenProject();
        mojo.project.getModel().setBuild(new Build());
        mojo.project.getBuild().setDirectory(base.resolve("target").toString());
        mojo.project.getBuild().setOutputDirectory(base.resolve("target/classes").toString());
        mojo.javaRelease = 21;
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                messages.add(content.toString());
            }

            @Override
            public void error(CharSequence content) {
                errors.add(content.toString());
            }
        });
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
        assertTrue(Files.exists(base.resolve("target/axl/generated-sources_axl-from-src_main_axl.state.json")));
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

    @Test
    void executionsSharingAnOutputDirectoryKeepEachOthersFiles(@TempDir Path base) throws Exception {
        Files.writeString(Files.createDirectories(base.resolve("src/main/axl/app")).resolve("A.axl"),
                "package app\n\nfn a() -> Int = 1\n");
        Files.writeString(Files.createDirectories(base.resolve("src/extra/axl/extra")).resolve("E.axl"),
                "package extra\n\nfn e() -> Int = 2\n");
        for (int build = 0; build < 2; build++) {
            mojo(base).execute();
            GenerateMojo extra = mojo(base);
            extra.sourceDirectory = base.resolve("src/extra/axl").toFile();
            extra.execute();
        }
        assertEquals("Translated 0 of 1 .axl file(s)", messages.getLast());
        assertTrue(Files.exists(base.resolve("target/generated-sources/axl/app/A.java")));
        assertTrue(Files.exists(base.resolve("target/generated-sources/axl/extra/E.java")));
    }

    @Test
    void keepsErrorProneToTheGeneratedJavaUnlessCheckJava(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("my project");
        Files.writeString(Files.createDirectories(base.resolve("src/main/axl/app")).resolve("A.axl"),
                "package app\n\nfn a() -> Int = 1\n");
        GenerateMojo mojo = mojo(base);
        mojo.execute();
        String args = mojo.project.getProperties().getProperty("axl.errorProneArgs");
        assertTrue(args.startsWith("-XepExcludedPaths:") && !args.contains(" "), args);
        Pattern excluded = Pattern.compile(args.substring("-XepExcludedPaths:".length()));
        Path generated = base.resolve("target/generated-sources/axl/app/A.java").toRealPath();
        assertFalse(excluded.matcher(generated.toUri().getPath()).matches(), args);
        assertTrue(excluded.matcher(base.resolve("src/main/java/app/B.java").toAbsolutePath().toUri().getPath()).matches(), args);

        // A second execution into another directory keeps the first one's files checked.
        GenerateMojo extra = configure(new GenerateMojo(), base);
        extra.project = mojo.project;
        extra.outputDirectory = base.resolve("target/generated-sources/extra").toFile();
        extra.execute();
        excluded = Pattern.compile(mojo.project.getProperties().getProperty("axl.errorProneArgs").substring("-XepExcludedPaths:".length()));
        assertFalse(excluded.matcher(generated.toUri().getPath()).matches());
        assertFalse(excluded.matcher(base.resolve("target/generated-sources/extra/app/A.java").toRealPath().toUri().getPath()).matches());

        GenerateMojo checkingJava = mojo(base);
        checkingJava.checkJava = true;
        checkingJava.execute();
        assertEquals("", checkingJava.project.getProperties().getProperty("axl.errorProneArgs"));
    }

    @Test
    void escapesEqualsSignsInTheJavacPluginArgument(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("eq=1");
        Files.writeString(Files.createDirectories(base.resolve("src/main/axl/app")).resolve("A.axl"),
                "package app\n\nfn a() -> Int = 1\n");
        GenerateMojo mojo = mojo(base);
        mojo.execute();
        String option = mojo.project.getProperties().getProperty("axl.javacPlugin");
        assertTrue(option.contains("eq%3D1/target/generated-sources/axl") && !option.contains("eq=1"), option);
    }

    @Test
    void namesAFileThatIsNotUtf8(@TempDir Path base) throws Exception {
        Path file = Files.createDirectories(base.resolve("src/main/axl/app")).resolve("A.axl");
        Files.write(file, new byte[] {'f', 'n', ' ', (byte) 0xff});
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> mojo(base).execute());
        assertEquals(file + " is not UTF-8 text", e.getCause().getMessage());
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
    void readsTheUnchangedFilesWhenTranslatingAChangedOne(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("Greeter.axl"), "package app\n\ninterface Greeter {\n    val name: String\n}\n");
        Files.writeString(sources.resolve("Terse.axl"), "package app\n\nclass Terse : Greeter {\n    val name: String = \"terse\"\n}\n");
        Path terse = base.resolve("target/generated-sources/axl/app/Terse.java");
        mojo(base).execute();
        assertTrue(Files.readString(terse).contains("public String name()"));

        Files.writeString(sources.resolve("Terse.axl"), "package app\n\nclass Terse : Greeter {\n    val name: String = \"brief\"\n}\n");
        mojo(base).execute();
        assertEquals("Translated 2 of 2 .axl file(s)", messages.getLast());
        assertTrue(Files.readString(terse).contains("public String name()"));
    }

    @Test
    void retranslatesThePackageOfAChangedFile(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Path greeter = sources.resolve("Greeter.axl");
        Files.writeString(greeter, "package app\n\ninterface Greeter {\n    val name: String\n}\n");
        Files.writeString(sources.resolve("Terse.axl"), "package app\n\nclass Terse : Greeter {\n    val name: String = \"terse\"\n}\n");
        Files.writeString(Files.createDirectories(base.resolve("src/main/axl/other")).resolve("Other.axl"),
                "package other\n\nfn other() -> Int = 1\n");
        mojo(base).execute();

        Files.writeString(greeter, "package app\n\ninterface Greeter {\n    val name: String\n    fn greet() -> String\n}\n");
        assertThrows(MojoFailureException.class, () -> mojo(base).execute());
        assertEquals("Translated 2 of 3 .axl file(s)", messages.getLast());
        assertEquals(List.of(sources.resolve("Terse.axl") + ":3:7: error: `Terse` does not give `fn greet() -> String`, "
                + "which the interface `Greeter` requires; add the method [AXL017]"), errors);

        // Without the interface, Terse is a class without supertypes to check; javac will miss it.
        Files.delete(greeter);
        mojo(base).execute();
        assertEquals("Translated 1 of 2 .axl file(s)", messages.getLast());
    }

    @Test
    void testSourcesReadTheDeclarationsOfTheMainSources(@TempDir Path base) throws Exception {
        Path options = Files.createDirectories(base.resolve("src/main/axl/app")).resolve("Options.axl");
        Files.writeString(options, "package app\n\nsealed interface Option<T> {\n    val present: Boolean\n}\n\n"
                + "data class Some<T>(val present: Boolean, val value: T) : Option<T>\n\n"
                + "fn parse(s: String) -> Option<Int> = Some<Int>(true, s.length())\n");
        Files.writeString(Files.createDirectories(base.resolve("src/test/axl/app")).resolve("Checks.axl"),
                "package app\n\nimport app.Options.Some\n\nfn check(s: String) -> Int {\n    val parsed = Options.parse(s)\n"
                + "    if (parsed is Some) {\n        return parsed.value() + 1\n    }\n    return 0\n}\n");
        Path checks = base.resolve("target/generated-test-sources/axl/app/Checks.java");
        GenerateTestMojo mojo = testMojo(base);
        mojo.execute();
        assertTrue(Files.readString(checks).contains("Some<Integer>"), Files.readString(checks));
        assertEquals(List.of(base.resolve("target/generated-test-sources/axl").toString()), mojo.project.getTestCompileSourceRoots());

        // A changed main declaration makes the tests translated again.
        Files.writeString(options, "package app\n\nsealed interface Option {\n    val present: Boolean\n}\n\n"
                + "data class Some(val present: Boolean, val value: Int) : Option\n\n"
                + "fn parse(s: String) -> Option = Some(true, s.length())\n");
        testMojo(base).execute();
        assertEquals("Translated 1 of 1 .axl file(s)", messages.getLast());
        assertFalse(Files.readString(checks).contains("Some<Integer>"), Files.readString(checks));
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
        assertTrue(Files.notExists(base.resolve("target/axl/generated-sources_axl-from-src_main_axl.state.json")));
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
    void rejectsAClassDeclaredAgainByANewFile(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl"));
        Path x = Files.createDirectories(sources.resolve("x")).resolve("Util.axl");
        Path y = Files.createDirectories(sources.resolve("y")).resolve("Util.axl");
        Files.writeString(x, "package p\n\nfn a() -> Int = 1\n");
        mojo(base).execute();

        Files.writeString(y, "package p\n\nfn b() -> Int = 2\n");
        List<String> errors = new ArrayList<>();
        GenerateMojo mojo = mojo(base);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void error(CharSequence content) {
                errors.add(content.toString());
            }
        });
        assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals(x + ":1:1: error: the class `p.Util` is declared by several files: " + x + ", " + y + " [AXL015]", errors.getFirst());

        Files.delete(y);
        mojo(base).execute();
        assertTrue(Files.readString(base.resolve("target/generated-sources/axl/p/Util.java")).contains("a()"));
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

    @Test
    void leavesOutParamChecksWhenTurnedOff(@TempDir Path base) throws Exception {
        Path sources = Files.createDirectories(base.resolve("src/main/axl/app"));
        Files.writeString(sources.resolve("A.axl"), "package app\n\nfn a(s: String) -> Int = s.length()\n");
        Path generated = base.resolve("target/generated-sources/axl/app/A.java");
        mojo(base).execute();
        assertTrue(Files.readString(generated).contains("Nulls.param(s, \"s\");"), Files.readString(generated));
        GenerateMojo unchecked = mojo(base);
        unchecked.paramChecks = false;
        unchecked.execute();
        assertEquals("Translated 1 of 1 .axl file(s)", messages.getLast());
        assertFalse(Files.readString(generated).contains("Nulls.param"), Files.readString(generated));
    }
}

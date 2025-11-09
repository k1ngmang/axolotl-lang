package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliTest {

    private static final Path CASES = Path.of(System.getProperty("axl.conformance"));

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int axl(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void runPrintsGreetingAndResult() {
        int exit = axl("run", CASES.resolve("hello/src/app/Main.axl").toString());
        assertEquals(0, exit, err.toString());
        assertEquals("Hello, stranger\nOk[value=42]\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void buildWritesClassesSourcesAndLineMaps(@TempDir Path tmp) {
        int exit = axl("build", CASES.resolve("hello/src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString());
        assertEquals(0, exit, err.toString());
        assertTrue(Files.exists(tmp.resolve("classes/util/Parse.class")));
        assertTrue(Files.exists(tmp.resolve("gen/util/Parse.java")));
        assertTrue(Files.exists(tmp.resolve("gen/util/Parse.axlmap")));
    }

    @Test
    void buildReportsErrorsOnAxlLines(@TempDir Path tmp) {
        int exit = axl("build", CASES.resolve("javac_error_mapping/src").toString(), "-d", tmp.toString());
        assertEquals(1, exit);
        String message = err.toString(StandardCharsets.UTF_8).strip();
        assertTrue(message.endsWith("Main.axl:6:22: error: `greeting` of type `String` has no method `lenght()`"), message);
    }

    @Test
    void runShowsAxlLinesInStackTraces(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("app")).resolve("Main.axl");
        Files.writeString(main, "package app\n\nfn main() {\n    println(Integer.parseInt(\"x\"))\n}\n");
        assertEquals(1, axl("run", main.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("at app.Main.main(Main.axl:4)"), err.toString());
    }

    @Test
    void retraceRewritesFramesFromClassesWithSmap(@TempDir Path tmp) throws Exception {
        assertEquals(0, axl("build", CASES.resolve("hello/src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString()));
        Path trace = tmp.resolve("trace.txt");
        Files.writeString(trace, "java.lang.IllegalStateException\n\tat util.Parse.doubled(Parse.java:15)\n");
        out.reset();
        assertEquals(0, axl("retrace", "--classpath", tmp.resolve("classes").toString(), trace.toString()));
        assertEquals("java.lang.IllegalStateException\n\tat util.Parse.doubled(Parse.axl:9)\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkReportsErrorsOfSeveralSourceDirectories(@TempDir Path tmp) throws Exception {
        Path axl = Files.createDirectories(tmp.resolve("axl/app"));
        Path java = Files.createDirectories(tmp.resolve("java/app"));
        Files.writeString(java.resolve("Names.java"), "package app;\npublic class Names { public static String name() { return \"x\"; } }\n");
        Files.writeString(axl.resolve("Main.axl"), "package app\n\nfn main() {\n    val n: String? = null\n    println(n.length() + Names.name())\n}\n");
        assertEquals(1, axl("check", tmp.resolve("axl").toString(), tmp.resolve("java").toString()));
        assertEquals(axl.resolve("Main.axl").toAbsolutePath() + ":5:15: error: value `n` may be null: use `?.`, `?:` or check `if (n != null)`",
                out.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void strictNullIsOffUnlessAsked(@TempDir Path tmp) {
        String src = CASES.resolve("strict_null_errors/src").toString();
        assertEquals(0, axl("build", src, "-d", tmp.resolve("lenient").toString()), err.toString());
        assertEquals(1, axl("build", src, "-d", tmp.resolve("strict").toString(), "--strict-null"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Main.axl:7:18: error: `home` may be null"), err.toString());
    }

    @Test
    void sourceRootStripsPackageDirectories() {
        Path file = Path.of("/work/src/app/util/Main.axl");
        assertEquals(Path.of("/work/src"), RunCommand.sourceRoot(file, "app.util"));
        assertEquals(Path.of("/work/src/app/util"), RunCommand.sourceRoot(file, "other"));
    }
}

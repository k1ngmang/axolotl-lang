package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
            return Main.commandLine().execute(args);
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
    void runPassesEverythingAfterTheFileToTheProgram() {
        CommandLine commandLine = Main.commandLine();
        commandLine.parseArgs("run", "--no-null-checks", "A.axl", "one", "--verbose", "--", "-x");
        RunCommand run = commandLine.getSubcommands().get("run").getCommand();
        assertTrue(run.noNullChecks);
        assertEquals(Path.of("A.axl"), run.file);
        assertEquals(List.of("one", "--verbose", "--", "-x"), run.args);

        commandLine = Main.commandLine();
        commandLine.parseArgs("run", "--", "-A.axl", "-x");
        run = commandLine.getSubcommands().get("run").getCommand();
        assertEquals(Path.of("-A.axl"), run.file);
        assertEquals(List.of("-x"), run.args);
    }

    @Test
    void runKeepsItsClassesUntilALaterRunFindsThemUnused() throws Exception {
        Path finished = Files.createTempDirectory(RunDirectories.PREFIX);
        Files.createFile(finished.resolve(".lock"));
        Path running = RunDirectories.create();
        assertTrue(Files.notExists(finished));
        RunDirectories.create();
        assertTrue(Files.exists(running));
    }

    @Test
    void workDirectoriesOfOtherRunsAreNotSources() {
        assertTrue(Paths.isInWorkDirectory(Paths.TEMP.resolve("axl-run123/generated/app/Main.java")));
        assertTrue(Paths.isInWorkDirectory(Paths.TEMP.resolve("axl-check7/generated/Main.java")));
        assertTrue(!Paths.isInWorkDirectory(Paths.TEMP.resolve("app/Main.axl")));
        assertTrue(!Paths.isInWorkDirectory(Path.of("src/axl-run1/Main.axl")));
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
    void rejectsMissingSourceDirectoriesAndFiles(@TempDir Path tmp) throws Exception {
        assertEquals(2, axl("build", tmp.resolve("missing").toString(), "-d", tmp.resolve("out").toString()));
        assertEquals(2, axl("check", tmp.resolve("missing").toString()));
        assertEquals(2, axl("run", tmp.resolve("Missing.axl").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("axl: no such directory: "), err.toString());
    }

    @Test
    void reportsUnusableOutputDirectoriesAndNonUtf8Sources(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        Path file = Files.writeString(tmp.resolve("file"), "");
        assertEquals(2, axl("build", tmp.resolve("src").toString(), "-d", file.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString(), "--no-null-checks"));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("axl: "), err.toString());

        Files.write(app.resolve("Main.axl"), new byte[] {'f', 'n', ' ', (byte) 0xff});
        err.reset();
        assertEquals(2, axl("build", tmp.resolve("src").toString(), "-d", tmp.resolve("out").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Main.axl is not UTF-8 text"), err.toString());
        err.reset();
        assertEquals(2, axl("run", app.resolve("Main.axl").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Main.axl is not UTF-8 text"), err.toString());
    }

    @Test
    void failsOnJavaSourcesThatAreNotUtf8(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        byte[] text = "package app;\nclass L { String s = \"\u00e9\"; }\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(app.resolve("L.java"), text);
        assertEquals(1, axl("build", tmp.resolve("src").toString(), "-d", tmp.resolve("out").toString(), "--no-null-checks"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unmappable character"), err.toString());
        assertEquals(1, axl("check", tmp.resolve("src").toString(), "--no-null-checks"));
    }

    @Test
    void runReportsAMissingMainAndInitializerFailures(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(app.resolve("Lib.axl"), "package app\n\nfn f() = println(1)\n");
        assertEquals(1, axl("run", app.resolve("Lib.axl").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("has no `fn main()`"), err.toString());

        Files.writeString(app.resolve("Init.axl"), "package app\n\nval limit: Int = Integer.parseInt(\"zz\")\n\nfn main() {\n    println(limit)\n}\n");
        err.reset();
        assertEquals(1, axl("run", app.resolve("Init.axl").toString()));
        String trace = err.toString(StandardCharsets.UTF_8);
        assertTrue(trace.contains("at app.Init.<clinit>(Init.axl:3)"), trace);
        assertTrue(!trace.contains("picocli") && !trace.contains("RunCommand"), trace);
    }

    @Test
    void buildIgnoresOutputsInsideTheSourceDirectory(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        assertEquals(0, axl("build", tmp.toString(), "-d", tmp.resolve("out").toString(), "--no-null-checks"), err.toString());
        Files.move(app.resolve("Main.axl"), app.resolve("Start.axl"));
        Files.writeString(app.resolve("Start.axl"), "package app\n\nfn main() {\n    println(2)\n}\n");
        assertEquals(0, axl("build", tmp.toString(), "-d", tmp.resolve("out").toString(), "--no-null-checks"), err.toString());
    }

    @Test
    void buildsWithTheOutputInTheSourceDirectoryOrAbove(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(Names.name())\n}\n");
        Files.writeString(app.resolve("Names.java"), "package app;\npublic class Names { public static String name() { return \"x\"; } }\n");
        String src = tmp.resolve("src").toString();
        assertEquals(0, axl("build", src, "-d", src, "--no-null-checks"), err.toString());
        assertTrue(Files.exists(app.resolve("Main.class")));
        assertTrue(Files.exists(app.resolve("Names.class")));
        assertEquals(0, axl("build", src, "-d", src, "--no-null-checks"), err.toString());

        assertEquals(0, axl("build", src, "-d", tmp.toString(), "--no-null-checks"), err.toString());
        assertTrue(Files.exists(tmp.resolve("app/Main.class")));
    }

    @Test
    void sourceRootStripsPackageDirectories() {
        Path file = Path.of("/work/src/app/util/Main.axl");
        assertEquals(Path.of("/work/src"), RunCommand.sourceRoot(file, "app.util"));
        assertEquals(Path.of("/work/src/app/util"), RunCommand.sourceRoot(file, "other"));
    }
}

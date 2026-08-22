package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;
import org.axl.compiler.LineMap;
import org.axl.javac.RuntimeClasspath;
import org.axl.smap.Smap;
import org.axl.smap.SmapInjector;
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
            return commandLine().execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    CommandLine commandLine() {
        return Main.commandLine();
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
    void everyCommandHasHelp() {
        for (String command : List.of("build", "run", "check", "retrace", "setup")) {
            out.reset();
            assertEquals(0, axl(command, "--help"), err.toString());
            assertTrue(out.toString().startsWith("Usage: axl " + command), out.toString());
        }
        out.reset();
        assertEquals(0, axl("help", "run"), err.toString());
        assertTrue(out.toString().startsWith("Usage: axl run"), out.toString());
    }

    @Test
    void runExplainsThatMainTakesNoParameters(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("Main.axl"),
                "package app\n\nimport java.util.List\n\nfn main(args: List<String>) {\n    println(args)\n}\n");
        assertEquals(1, axl("run", "--no-null-checks", file.toString()));
        assertTrue(err.toString().contains("`fn main` must have no parameters"), err.toString());
    }

    @Test
    void runExplainsThatMainTakesNoResultType(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("Main.axl"),
                "package app\n\nfn main() -> Int {\n    println(\"hi\")\n    return 5\n}\n");
        assertEquals(1, axl("run", "--no-null-checks", file.toString()));
        assertTrue(err.toString().contains("`fn main` must have no result type"), err.toString());
        assertFalse(err.toString().contains("must have no parameters"), err.toString());
    }

    @Test
    void runKeepsItsClassesUntilALaterRunFindsThemUnused() throws Exception {
        Path finished = Files.createTempDirectory(WorkDirectories.RUN);
        Files.createFile(finished.resolve(".lock"));
        Path running = WorkDirectories.create(WorkDirectories.RUN);
        assertTrue(Files.notExists(finished));
        WorkDirectories.create(WorkDirectories.RUN);
        assertTrue(Files.exists(running));
    }

    @Test
    void severalProcessesMayDeleteTheSameDirectory(@TempDir Path tmp) throws Exception {
        for (int round = 0; round < 20; round++) {
            Path dir = Files.createDirectories(tmp.resolve("work" + round + "/classes"));
            for (int i = 0; i < 200; i++) {
                Files.createFile(dir.resolve("F" + i + ".class"));
            }
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread thread = new Thread(() -> Paths.deleteRecursively(dir.getParent()));
                thread.setUncaughtExceptionHandler((th, e) -> failures.add(e));
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertEquals(List.of(), failures);
            assertTrue(Files.notExists(dir.getParent()));
        }
    }

    @Test
    void aWorkDirectoryBeingCreatedIsNotDeleted() throws Exception {
        Path creating = Files.createTempDirectory(WorkDirectories.RUN);
        Files.createFile(creating.resolve(".lock.new"));
        Path created = WorkDirectories.create(WorkDirectories.RUN);
        assertTrue(Files.exists(creating));
        assertTrue(Files.exists(created.resolve(".lock")));
        assertTrue(Files.notExists(created.resolve(".lock.new")));
        assertTrue(WorkDirectories.holds(created));
        Paths.deleteRecursively(creating);
    }

    @Test
    void missingFilesWithGarbledNamesGetTheLocaleHint() {
        String encoding = System.getProperty("sun.jnu.encoding");
        System.setProperty("sun.jnu.encoding", "ANSI_X3.4-1968");
        try {
            String message = Main.withEncodingHint("axl: no such file: ??n??/A.axl", "??n??/A.axl");
            assertEquals(File.separatorChar == '/', message.contains("LANG=C.UTF-8"), message);
        } finally {
            System.setProperty("sun.jnu.encoding", encoding);
        }
        assertEquals("axl: no such file: A.axl", Main.withEncodingHint("axl: no such file: A.axl", "A.axl"));
    }

    @Test
    void setupExplainsWhereTheLibrariesComeFromWithoutABundledRepository() {
        assertEquals(1, axl("setup"));
        assertTrue(err.toString().contains("publishToMavenLocal"), err.toString());
    }

    @Test
    void setupRejectsABlankRepository() {
        assertEquals(2, axl("setup", "--repository", ""));
        assertTrue(err.toString().contains("--repository needs a directory"), err.toString());
    }

    @Test
    void setupCopiesTheBundledRepositoryOverOlderFiles(@TempDir Path tmp) throws Exception {
        Path bundled = Files.createDirectories(tmp.resolve("bundled/org/axl/axl-runtime/0.1.0"));
        Files.writeString(bundled.resolve("axl-runtime-0.1.0.pom"), "new");
        Path old = Files.createDirectories(tmp.resolve("m2/org/axl/axl-runtime/0.1.0")).resolve("axl-runtime-0.1.0.pom");
        Files.writeString(old, "old");
        assertEquals(1, SetupCommand.copy(tmp.resolve("bundled"), tmp.resolve("m2")));
        assertEquals("new", Files.readString(old));
    }

    @Test
    void checkShowsWarningsOfHandWrittenJava(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/Main.axl"), "package app\n\nfn main() {\n    println(J.f(1))\n}\n");
        Files.writeString(tmp.resolve("app/J.java"), "package app;\n\nclass J {\n    static int f(int x) {\n"
                + "        int it = 5;\n        return x;\n    }\n}\n");
        assertEquals(0, axl("check", tmp.toString()), err.toString());
        String printed = out.toString(StandardCharsets.UTF_8) + err;
        assertTrue(printed.contains("J.java:5:13: warning: [UnusedVariable]"), printed);
    }

    @Test
    void checkDeletesDirectoriesLeftByAKilledCheck(@TempDir Path tmp) throws Exception {
        Path killed = Files.createTempDirectory(WorkDirectories.CHECK);
        Files.createFile(killed.resolve(".lock"));
        Files.writeString(tmp.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        assertEquals(0, axl("check", tmp.toString(), "--no-null-checks"), out.toString());
        assertTrue(Files.notExists(killed));
    }

    @Test
    void workDirectoriesOfOtherRunsAreNotSources() throws Exception {
        Path run = WorkDirectories.create(WorkDirectories.RUN);
        Path check = WorkDirectories.create(WorkDirectories.CHECK);
        assertTrue(Paths.isInWorkDirectory(run.resolve("generated/app/Main.java")));
        assertTrue(Paths.isInWorkDirectory(check.resolve("generated/Main.java")));
        WorkDirectories.delete(check);
        assertTrue(!Paths.isInWorkDirectory(Paths.TEMP.resolve("axl-check7/generated/Main.java")));
        assertTrue(!Paths.isInWorkDirectory(Paths.TEMP.resolve("app/Main.axl")));
        assertTrue(!Paths.isInWorkDirectory(Path.of("src/axl-run1/Main.axl")));
    }

    @Test
    void checksSourcesInATemporaryDirectoryNamedLikeAWorkDirectory() throws Exception {
        Path dir = Files.createTempDirectory(WorkDirectories.CHECK + "s-");
        Files.writeString(dir.resolve("Bad.axl"), "package app\n\nfn f() -> Int = \"x\"\n");
        try {
            assertEquals(1, axl("check", dir.toString(), "--no-null-checks"));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Bad.axl:3:"), out.toString());
        } finally {
            Paths.deleteRecursively(dir);
        }
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
        assertTrue(message.endsWith("Main.axl:6:22: error: `greeting` of type `String` has no method `lenght()`; did you mean `length`? [AXL100]"), message);
    }

    @Test
    void runShowsAxlLinesInStackTraces(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("app")).resolve("Main.axl");
        Files.writeString(main, "package app\n\nfn main() {\n    println(Integer.parseInt(\"x\"))\n}\n");
        assertEquals(1, axl("run", main.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("at app.Main.main(Main.axl:4)"), err.toString());
    }

    @Test
    void buildMakesPlainStackTracesNameAxlFilesAndLines(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(src.resolve("Main.axl"),
                "package app\n\nfn parse(s: String) -> Int = Integer.parseInt(s)\n\nfn main() {\n    println(\"start\")\n    println(parse(\"x\"))\n}\n");
        assertEquals(0, axl("build", tmp.resolve("src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString()), err.toString());

        String trace = runJava(tmp.resolve("classes"), "app.Main");
        assertTrue(trace.contains("java.lang.NumberFormatException: For input string: \"x\""), trace);
        assertTrue(trace.contains("at app.Main.parse(Main.axl:3)"), trace);
        assertTrue(trace.contains("at app.Main.main(Main.axl:7)"), trace);
        assertTrue(!trace.contains("Main.java"), trace);
    }

    @Test
    void buildWithoutSmapKeepsGeneratedJavaLines(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(src.resolve("Main.axl"),
                "package app\n\nfn main() {\n    println(Integer.parseInt(\"x\"))\n}\n");
        assertEquals(0, axl("build", tmp.resolve("src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString(), "--no-smap"), err.toString());

        String trace = runJava(tmp.resolve("classes"), "app.Main");
        assertTrue(trace.contains("at app.Main.main(Main.java:"), trace);
        assertTrue(!trace.contains("Main.axl"), trace);

        // Such a trace is what `axl retrace` is for.
        Path file = Files.writeString(tmp.resolve("trace.txt"), trace);
        out.reset();
        assertEquals(0, axl("retrace", "--map-dir", tmp.resolve("gen").toString(), file.toString()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("at app.Main.main(Main.axl:4)"), out.toString());
    }

    @Test
    void retraceLeavesTracesThatNameAxlFilesAsTheyAre(@TempDir Path tmp) throws Exception {
        assertEquals(0, axl("build", CASES.resolve("hello/src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString()));
        String trace = "java.lang.IllegalStateException\n\tat util.Parse.doubled(Parse.axl:9)\n";
        Path file = Files.writeString(tmp.resolve("trace.txt"), trace);
        out.reset();
        assertEquals(0, axl("retrace", "--map-dir", tmp.resolve("gen").toString(), "--classpath",
                tmp.resolve("classes").toString(), file.toString()));
        assertEquals(trace, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void retraceRewritesFramesFromClassesWithAnOlderSmap(@TempDir Path tmp) throws Exception {
        assertEquals(0, axl("build", CASES.resolve("hello/src").toString(), "-d", tmp.resolve("classes").toString(),
                "-s", tmp.resolve("gen").toString(), "--no-smap"));
        // What earlier versions of the build added: the SMAP only, with the Java lines kept.
        LineMap lineMap = LineMap.fromJson(Files.readString(tmp.resolve("gen/util/Parse.axlmap")));
        Path parse = tmp.resolve("classes/util/Parse.class");
        Files.write(parse, SmapInjector.withSmap(Files.readAllBytes(parse), Smap.of(lineMap, "Parse.java").format()));
        Path trace = tmp.resolve("trace.txt");
        Files.writeString(trace, "java.lang.IllegalStateException\n\tat util.Parse.doubled(Parse.java:15)\n");
        out.reset();
        assertEquals(0, axl("retrace", "--classpath", tmp.resolve("classes").toString(), trace.toString()));
        assertEquals("java.lang.IllegalStateException\n\tat util.Parse.doubled(Parse.axl:9)\n",
                out.toString(StandardCharsets.UTF_8));
    }

    /** Runs a class in its own JVM, as `java -cp` would, and returns what it printed to standard error. */
    private static String runJava(Path classes, String mainClass) throws Exception {
        String classpath = classes + File.pathSeparator + RuntimeClasspath.entries().getFirst();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, mainClass)
                .redirectErrorStream(false)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, process.waitFor(), stderr);
        return stderr;
    }

    @Test
    void retraceNamesAJarItCannotRead(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("bad.jar"), "not a jar");
        Path trace = Files.writeString(tmp.resolve("trace.txt"), "\tat app.Main.main(Main.java:3)\n");
        assertEquals(2, axl("retrace", "-cp", jar.toString(), trace.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("axl: " + jar + ": "), err.toString());
    }

    @Test
    void checkReportsErrorsOfSeveralSourceDirectories(@TempDir Path tmp) throws Exception {
        Path axl = Files.createDirectories(tmp.resolve("axl/app"));
        Path java = Files.createDirectories(tmp.resolve("java/app"));
        Files.writeString(java.resolve("Names.java"), "package app;\npublic class Names { public static String name() { return \"x\"; } }\n");
        Files.writeString(axl.resolve("Main.axl"), "package app\n\nfn main() {\n    val n: String? = null\n    println(n.length() + Names.name())\n}\n");
        assertEquals(1, axl("check", tmp.resolve("axl").toString(), tmp.resolve("java").toString()));
        assertEquals(axl.resolve("Main.axl").toAbsolutePath() + ":5:15: error: value `n` may be null: use `?.`, `?:` or check `if (n != null)` [AXL102]",
                out.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void checkShowsPathsWithoutDotDirectories(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(nope)\n}\n");
        assertEquals(1, axl("check", tmp.resolve("src/.").toString(), "--no-null-checks"));
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith(app.resolve("Main.axl").toAbsolutePath() + ":4:13: "), out.toString());
    }

    @Test
    void checkReadsEachFileOnceWhenSourceDirectoriesOverlap(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        Path src = tmp.resolve("src");
        assertEquals(0, axl("check", src.toString(), app.toString(), "--no-null-checks"), out.toString());
        assertEquals(0, axl("check", src.toString(), src.resolve(".").toString(), "--no-null-checks"), out.toString());
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
    void rejectsDirectoriesWithoutSourcesAndFilesGivenAsSourceDirectory(@TempDir Path tmp) throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertEquals(2, axl("check", empty.toString()));
        assertEquals(2, axl("build", empty.toString(), "-d", tmp.resolve("out").toString()));
        Path file = Files.writeString(Files.createDirectories(tmp.resolve("src/app")).resolve("Main.axl"), "package app\n");
        assertEquals(2, axl("check", file.toString()));
        String printed = err.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("axl: no .axl or .java files in " + empty), printed);
        assertTrue(printed.contains("axl: not a directory: " + file + "; give the source directory that holds the package "
                + "directories, such as src"), printed);
    }

    @Test
    void warnsAboutASourceDirectoryOneLevelTooDeep(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        assertEquals(0, axl("check", app.toString()));
        String printed = err.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("axl: warning: " + app.resolve("Main.axl") + " is in package `app`; give the source "
                + "directory that holds its package directories, such as " + tmp.resolve("src")), printed);
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
    void buildRemovesOutputsOfDeletedSourcesOnly(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        Files.writeString(app.resolve("Old.axl"), "package app\n\nfn x() -> Int = 1\n\nclass Box(val v: Int)\n");
        Path out = tmp.resolve("out");
        String src = tmp.resolve("src").toString();
        assertEquals(0, axl("build", src, "-d", out.toString(), "--no-null-checks"), err.toString());
        assertTrue(Files.exists(out.resolve("app/Old$Box.class")));
        Files.writeString(out.resolve("app/Mine.java"), "user file");
        Files.writeString(out.resolve("app/Mine.axlmap"), "{\"source\": \"app/Elsewhere.axl\", \"lines\": []}");

        Files.delete(app.resolve("Old.axl"));
        assertEquals(0, axl("build", src, "-d", out.toString(), "--no-null-checks"), err.toString());

        for (String name : List.of("Old.class", "Old$Box.class", "Old.java", "Old.axlmap")) {
            assertTrue(Files.notExists(out.resolve("app").resolve(name)), name);
        }
        assertTrue(Files.exists(out.resolve("app/Main.class")));
        assertTrue(Files.exists(out.resolve("app/Mine.java")));
        assertTrue(Files.exists(out.resolve("app/Mine.axlmap")));
    }

    @Test
    void buildKeepsOutputsOfAnotherSourceDirectory(@TempDir Path tmp) throws Exception {
        Path one = Files.createDirectories(tmp.resolve("one/app"));
        Path two = Files.createDirectories(tmp.resolve("two/app"));
        Files.writeString(one.resolve("A.axl"), "package app\n\nfn a() -> Int = 1\n");
        Files.writeString(two.resolve("B.axl"), "package app\n\nfn b() -> Int = 2\n");
        String out = tmp.resolve("out").toString();
        assertEquals(0, axl("build", tmp.resolve("one").toString(), "-d", out, "--no-null-checks"), err.toString());
        assertEquals(0, axl("build", tmp.resolve("two").toString(), "-d", out, "--no-null-checks"), err.toString());
        assertEquals(0, axl("build", tmp.resolve("two").toString(), "-d", out, "--no-null-checks"), err.toString());
        assertTrue(Files.exists(tmp.resolve("out/app/A.class")));
        assertTrue(Files.exists(tmp.resolve("out/app/B.class")));
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
    void warnsAboutMissingClasspathEntries(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"), "package app\n\nfn main() {\n    println(1)\n}\n");
        Path missing = tmp.resolve("missing.jar");
        assertEquals(0, axl("build", tmp.resolve("src").toString(), "-d", tmp.resolve("out").toString(),
                "-cp", missing.toString(), "--no-null-checks"), err.toString());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("axl: warning: classpath entry not found: " + missing),
                err.toString());
    }

    @Test
    void runFindsClassesOfTheClasspathWhenCompilingAndRunning(@TempDir Path tmp) throws Exception {
        Path jar = libraryJar(tmp);
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Path main = Files.writeString(app.resolve("Main.axl"),
                "package app\n\nimport lib.Greeter\n\nfn main() {\n    println(Greeter.greet(\"Ann\"))\n}\n");
        assertEquals(1, axl("run", main.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown package `lib`"), err.toString());

        err.reset();
        assertEquals(0, axl("run", "-cp", jar.toString(), main.toString(), "ignored"), err.toString());
        assertEquals("Hello, Ann\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void runLeavesOutTheSourcesOfAnotherTreeUnderItsRoot(@TempDir Path tmp) throws Exception {
        Path jar = libraryJar(tmp);
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Path main = Files.writeString(app.resolve("Main.axl"),
                "package app\n\nimport lib.Greeter\n\nfn main() {\n    println(Greeter.greet(\"Ann\"))\n}\n");
        Path vendored = Files.createDirectories(tmp.resolve("src/vendor/lib"));
        Files.writeString(vendored.resolve("Greeter.axl"), "package lib\n\nfn greet(name: String) -> String = \"vendored \" + name\n");
        assertEquals(0, axl("run", "--verbose", "--no-null-checks", "-cp", jar.toString(), main.toString()), err.toString());
        assertEquals("Hello, Ann\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        String note = err.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");
        assertEquals("axl: not compiled: " + vendored.resolve("Greeter.axl") + " is in package `lib`, which is not its directory under "
                + tmp.resolve("src") + ", so it belongs to another source tree", note);
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("AXL014"), err.toString());
    }

    @Test
    void checkFindsClassesOfTheClasspath(@TempDir Path tmp) throws Exception {
        Path jar = libraryJar(tmp);
        Path app = Files.createDirectories(tmp.resolve("src/app"));
        Files.writeString(app.resolve("Main.axl"),
                "package app\n\nimport lib.Greeter\n\nfn main() {\n    println(Greeter.greet(\"Ann\"))\n}\n");
        assertEquals(0, axl("check", "--classpath", jar.toString(), tmp.resolve("src").toString()), out.toString());
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    /** A jar with `lib.Greeter`, a class no source directory of the test has. */
    private static Path libraryJar(Path tmp) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("lib-src/lib")).resolve("Greeter.java");
        Files.writeString(source, "package lib;\npublic class Greeter { public static String greet(String name) { return \"Hello, \" + name; } }\n");
        Path classes = tmp.resolve("lib-classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()));
        Path jar = tmp.resolve("greeter.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("lib/Greeter.class"));
            out.write(Files.readAllBytes(classes.resolve("lib/Greeter.class")));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void reportsAnInvalidPathWithoutTheUsage() {
        assertEquals(2, axl("build", "bad\0dir", "-d", "out"));
        String printed = err.toString(StandardCharsets.UTF_8);
        assertTrue(printed.startsWith("axl: invalid path: "), printed);
        assertTrue(!printed.contains("Usage:"), printed);
    }

    @Test
    void runRejectsFilesThatAreNotAxl(@TempDir Path tmp) throws Exception {
        Path java = Files.writeString(tmp.resolve("Util.java"), "class Util {}\n");
        assertEquals(2, axl("run", java.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("axl: not an .axl file: " + java), err.toString());
    }

    @Test
    void sourceRootStripsPackageDirectories() {
        Path file = Path.of("/work/src/app/util/Main.axl");
        assertEquals(Path.of("/work/src"), RunCommand.sourceRoot(file, "app.util"));
        assertEquals(Path.of("/work/src/app/util"), RunCommand.sourceRoot(file, "other"));
    }
}

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
        assertTrue(message.endsWith("Main.axl:6:5: error: cannot find symbol; symbol: method lenght(); "
                + "location: variable greeting of type java.lang.String"), message);
    }

    @Test
    void sourceRootStripsPackageDirectories() {
        Path file = Path.of("/work/src/app/util/Main.axl");
        assertEquals(Path.of("/work/src"), RunCommand.sourceRoot(file, "app.util"));
        assertEquals(Path.of("/work/src/app/util"), RunCommand.sourceRoot(file, "other"));
    }
}

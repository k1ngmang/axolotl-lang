package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Options;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every case in tests/conformance. A case has a `src` tree and any of:
 * `expected/` with the exact generated .java files, `expected.err` with the expected errors
 * (`CODE file:line:column: error: message`, one per line), `expected.out` with the output of the
 * `main` class named in `case.properties`, which may also set `inlineElvis=true` and `strictNull=true`.
 */
class ConformanceTest {

    private static final Path CASES = Path.of(System.getProperty("axl.conformance", "../tests/conformance"));
    private static final boolean UPDATE = Boolean.getBoolean("axl.updateGolden");

    @TestFactory
    Stream<DynamicTest> conformance() throws IOException {
        try (Stream<Path> dirs = Files.list(CASES)) {
            return dirs.filter(Files::isDirectory).sorted()
                    .map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> runCase(dir)))
                    .toList().stream();
        }
    }

    private void runCase(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path work = Files.createTempDirectory("axl-conformance");
        Path generated = work.resolve("generated");
        Path classes = work.resolve("classes");
        Properties props = caseProperties(dir);
        AxlBuild.Result result = new AxlBuild(src, generated, classes)
                .display(p -> src.relativize(p).toString().replace('\\', '/'))
                .options(new Options(Boolean.parseBoolean(props.getProperty("inlineElvis"))))
                .strictNull(Boolean.parseBoolean(props.getProperty("strictNull")))
                .run();

        List<String> errors = result.diagnostics().stream().filter(AxlDiagnostic::isError)
                .map(d -> d.code().id() + " " + d.format()).toList();
        Path expectedErr = dir.resolve("expected.err");
        if (Files.exists(expectedErr)) {
            assertFalse(result.success(), "expected the build to fail");
            assertEquals(nonBlankLines(Files.readString(expectedErr)), errors);
        } else {
            assertEquals(List.of(), errors);
            assertTrue(result.success());
        }

        checkGolden(dir, result, Files.exists(expectedErr));

        Path expectedOut = dir.resolve("expected.out");
        if (Files.exists(expectedOut)) {
            assertEquals(Files.readString(expectedOut).strip(), runMain(props.getProperty("main"), classes).strip());
        }
    }

    private void checkGolden(Path dir, AxlBuild.Result result, boolean errorCase) throws IOException {
        Path expected = dir.resolve("expected");
        for (GeneratedSource source : result.generated()) {
            Path golden = expected.resolve(source.unit().javaPath());
            String actual = source.unit().javaSource();
            if (UPDATE && !errorCase) {
                Files.createDirectories(golden.getParent());
                Files.writeString(golden, actual);
            } else if (Files.exists(golden)) {
                assertEquals(Files.readString(golden), actual, "generated " + source.unit().javaPath());
            } else if (!errorCase) {
                fail("missing golden file " + dir.relativize(golden));
            }
        }
    }

    private static Properties caseProperties(Path dir) throws IOException {
        Properties props = new Properties();
        Path file = dir.resolve("case.properties");
        if (Files.exists(file)) {
            props.load(new StringReader(Files.readString(file)));
        }
        return props;
    }

    private String runMain(String mainClass, Path classes) throws Exception {
        PrintStream original = System.out;
        var out = new ByteArrayOutputStream();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> nonBlankLines(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }
}

package org.axl.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxlPluginFunctionalTest {

    private static final Path SAMPLES = Path.of(System.getProperty("axl.samples"));
    private static final Path TEST_REPO = Path.of(System.getProperty("axl.testRepo"));

    @TempDir
    Path project;

    @BeforeEach
    void copySample() throws IOException {
        Path sample = SAMPLES.resolve("multi-module-gradle");
        try (Stream<Path> files = Files.walk(sample)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = project.resolve(sample.relativize(file).toString());
                if (target.toString().contains("/build/") || target.toString().contains("/.gradle/")) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // The test repository comes first so that no stale snapshot from ~/.m2 is used.
        String repo = TEST_REPO.toUri().toString();
        Files.writeString(project.resolve("init.gradle"), """
                beforeSettings { settings ->
                    settings.pluginManagement.repositories { maven { url = '%s' } }
                    settings.dependencyResolutionManagement.repositories { maven { url = '%s' } }
                }
                """.formatted(repo, repo));
    }

    private GradleRunner gradle(String... args) {
        List<String> arguments = new ArrayList<>(List.of("--init-script", "init.gradle", "--stacktrace"));
        arguments.addAll(List.of(args));
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(arguments).forwardOutput();
    }

    @Test
    void buildsMixedModulesAndRunsTests() {
        BuildResult result = gradle("build", ":app:run").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:generateAxl").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:generateTestAxl").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:test").getOutcome());
        assertTrue(result.getOutput().contains("Ok[value=hello-axolotl]"), result.getOutput());
        assertTrue(result.getOutput().contains("error: missing title"), result.getOutput());
        assertTrue(Files.exists(project.resolve("core/build/generated/axl/main/core/Titles.axlmap")));
    }

    @Test
    void secondBuildIsUpToDateAndCleanBuildComesFromCache() {
        gradle("build", "--build-cache").build();
        BuildResult again = gradle("build", "--build-cache").build();
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":core:generateAxl").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":core:compileJava").getOutcome());

        BuildResult cached = gradle("clean", "build", "--build-cache").build();
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":core:generateAxl").getOutcome());
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":core:compileJava").getOutcome());
    }

    @Test
    void changingOneFileRegeneratesOnlyIt() throws IOException {
        Files.writeString(project.resolve("core/src/main/axl/core/Extra.axl"), "package core\n\nfn extra() -> Int = 1\n");
        gradle(":core:generateAxl").build();
        Path titles = project.resolve("core/build/generated/axl/main/core/Titles.java");
        Path extra = project.resolve("core/build/generated/axl/main/core/Extra.java");
        long titlesModified = Files.getLastModifiedTime(titles).toMillis();

        Files.writeString(project.resolve("core/src/main/axl/core/Extra.axl"), "package core\n\nfn extra() -> Int = 2\n");
        BuildResult result = gradle(":core:generateAxl", "--info").build();

        assertTrue(result.getOutput().contains("Translated 1 .axl file(s)"), result.getOutput());
        assertEquals(titlesModified, Files.getLastModifiedTime(titles).toMillis());
        assertTrue(Files.readString(extra).contains("return 2;"));

        Files.delete(project.resolve("core/src/main/axl/core/Extra.axl"));
        gradle(":core:generateAxl").build();
        assertTrue(Files.notExists(extra));
        assertTrue(Files.exists(titles));
    }

    @Test
    void reportsTranslationErrorsWithAxlPath() throws IOException {
        Path main = project.resolve("app/src/main/axl/app/Main.axl");
        Files.writeString(main, Files.readString(main).replace("fn main() {", "fn main() {\n    return try title(\"x\")"));
        BuildResult result = gradle(":app:compileJava").buildAndFail();
        assertTrue(result.getOutput().contains(main.toAbsolutePath() + ":6:12: error: `return try e`"), result.getOutput());
    }

    @Test
    void reportsJavacErrorsWithAxlPath() throws IOException {
        Path main = project.resolve("app/src/main/axl/app/Main.axl");
        Files.writeString(main, Files.readString(main).replace("println(title(\" Hello Axolotl \"))",
                "val missing: String? = null\n    println(missing.length())"));
        BuildResult result = gradle(":app:compileJava").buildAndFail();
        assertTrue(result.getOutput().contains(main.toAbsolutePath() + ":7: error: value `missing` may be null"),
                result.getOutput());
    }

    @Test
    void addsSmapAndShowsAxlLinesInFailedTests() throws IOException {
        Files.writeString(project.resolve("core/src/main/axl/core/Numbers.axl"),
                "package core\n\nfn number(text: String) -> Int {\n    return Integer.parseInt(text)\n}\n");
        Files.writeString(project.resolve("core/src/test/java/core/NumbersTest.java"), """
                package core;

                import org.junit.jupiter.api.Test;

                class NumbersTest {
                    @Test
                    void fails() {
                        Numbers.number("not a number");
                    }
                }
                """);
        BuildResult result = gradle(":core:test").buildAndFail();
        assertTrue(result.getOutput().contains("NumbersTest > fails() FAILED"), result.getOutput());
        String report = Files.readString(project.resolve("core/build/test-results/test/TEST-core.NumbersTest.xml"));
        assertTrue(report.contains("at core.Numbers.number(Numbers.axl:4)"), report);
        byte[] classFile = Files.readAllBytes(project.resolve("core/build/classes/java/main/core/Numbers.class"));
        assertTrue(new String(classFile, java.nio.charset.StandardCharsets.ISO_8859_1).contains("*S Axolotl"));
    }

    @Test
    void writesLanguageServerConfig() throws IOException {
        gradle(":core:axlLspConfig").build();
        String json = Files.readString(project.resolve("core/build/axl/axl.json"));
        assertTrue(json.contains(project.resolve("core/src/main/axl").toString()), json);
        assertTrue(json.contains(project.resolve("core/src/main/java").toString()), json);
        assertTrue(json.contains("axl-runtime"), json);
        assertTrue(!json.contains("generated"), json);
    }

    @Test
    void reusesConfigurationCache() {
        gradle("build", "--configuration-cache").build();
        BuildResult again = gradle("build", "--configuration-cache").build();
        assertTrue(again.getOutput().contains("Reusing configuration cache."), again.getOutput());
    }

    @Test
    void worksWithGradle85() {
        BuildResult result = gradle("build").withGradleVersion("8.5").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:test").getOutcome());
    }
}

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
        // Gradle prints the long form of a Windows path; the temporary directory may be in the 8.3 form.
        project = project.toRealPath();
        Path sample = SAMPLES.resolve("multi-module-gradle");
        try (Stream<Path> files = Files.walk(sample)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = sample.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("build/") || relative.contains("/build/") || relative.startsWith(".gradle/")) {
                    continue;
                }
                Path target = project.resolve(relative);
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

    private static String jsonString(Path path) {
        return "\"" + jsonPath(path) + "\"";
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
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
    void changingAFileRetypesValuesInferredFromIt() throws IOException {
        Files.writeString(project.resolve("core/src/main/axl/core/Maker.axl"), "package core\n\nfn make() -> String = \"x\"\n");
        Files.writeString(project.resolve("core/src/main/axl/core/Values.axl"), "package core\n\nval made = Maker.make()\n");
        gradle(":core:compileJava").build();
        Files.writeString(project.resolve("core/src/main/axl/core/Maker.axl"), "package core\n\nfn make() -> Int = 1\n");
        gradle(":core:compileJava").build();
        assertTrue(Files.readString(project.resolve("core/build/generated/axl/main/core/Values.java")).contains("int made"));
    }

    @Test
    void removingAFileRetranslatesValuesInferredFromIt() throws IOException {
        Path maker = project.resolve("core/src/main/axl/core/Maker.axl");
        Path values = project.resolve("core/src/main/axl/core/Values.axl");
        Files.writeString(maker, "package core\n\nfn make() -> String = \"x\"\n");
        Files.writeString(values, "package core\n\nval made = Maker.make()\n");
        gradle(":core:generateAxl").build();
        Files.delete(maker);
        BuildResult result = gradle(":core:generateAxl").buildAndFail();
        assertTrue(result.getOutput().contains(values.toAbsolutePath() + ":3:"), result.getOutput());
    }

    @Test
    void rejectsAClassDeclaredAgainByANewFile() throws IOException {
        Path x = Files.createDirectories(project.resolve("core/src/main/axl/x")).resolve("Util.axl");
        Path y = Files.createDirectories(project.resolve("core/src/main/axl/y")).resolve("Util.axl");
        Files.writeString(x, "package p\n\nfn a() -> Int = 1\n");
        gradle(":core:generateAxl").build();

        Files.writeString(y, "package p\n\nfn b() -> Int = 2\n");
        BuildResult result = gradle(":core:generateAxl").buildAndFail();
        assertTrue(result.getOutput().contains("the class `p.Util` is declared by several files: "
                + x.toAbsolutePath() + ", " + y.toAbsolutePath()), result.getOutput());

        Files.delete(y);
        gradle(":core:generateAxl").build();
        assertTrue(Files.readString(project.resolve("core/build/generated/axl/main/p/Util.java")).contains("a()"));
    }

    @Test
    void rejectsTheSamePathInTwoSourceDirectories() throws IOException {
        Path build = project.resolve("core/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + "\nsourceSets.main { axl.srcDir(\"src/extra/axl\") }\n");
        Path main = Files.createDirectories(project.resolve("core/src/main/axl/lib")).resolve("Lib.axl");
        Path extra = Files.createDirectories(project.resolve("core/src/extra/axl/lib")).resolve("Lib.axl");
        Files.writeString(main, "package lib\n\nfn twice(x: Int) -> Int = x * 3\n");
        Files.writeString(extra, "package lib\n\nfn twice(x: Int) -> Int = x * 2\n");
        BuildResult result = gradle(":core:generateAxl").buildAndFail();
        assertTrue(result.getOutput().contains("the class `lib.Lib` is declared by several files: "
                + main.toAbsolutePath() + ", " + extra.toAbsolutePath()), result.getOutput());
    }

    @Test
    void readsJavaSourcesGeneratedByAnotherTask() throws IOException {
        Path build = project.resolve("core/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + """

                val genJava by tasks.registering {
                    val dir = layout.buildDirectory.dir("gen-java")
                    outputs.dir(dir)
                    doLast {
                        val file = dir.get().file("core/Gen.java").asFile
                        file.parentFile.mkdirs()
                        file.writeText("package core;\\npublic class Gen { public static String name() { return \\"gen\\"; } }\\n")
                    }
                }
                sourceSets.main { java.srcDir(genJava) }
                """);
        Files.writeString(project.resolve("core/src/main/axl/core/UsesGen.axl"), "package core\n\nval genName = Gen.name()\n");
        gradle(":core:compileJava", "--configuration-cache").build();
        assertTrue(Files.readString(project.resolve("core/build/generated/axl/main/core/UsesGen.java")).contains("String genName"));
    }

    @Test
    void customSourceSetsGetTheRuntime() throws IOException {
        Path build = project.resolve("core/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + "\nval integration by sourceSets.creating\n");
        Path dir = Files.createDirectories(project.resolve("core/src/integration/axl/it"));
        Files.writeString(dir.resolve("Check.axl"), "package it\n\nfn check(text: String?) -> String = text ?: \"none\"\n");
        BuildResult result = gradle(":core:compileIntegrationJava", ":core:dependencies", "--configuration", "integrationRuntimeClasspath")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:compileIntegrationJava").getOutcome());
        assertTrue(result.getOutput().contains("org.axl:axl-smap"), result.getOutput());
    }

    @Test
    void togglingSmapRecompiles() throws IOException {
        Path build = project.resolve("core/build.gradle.kts");
        String original = Files.readString(build);
        Path titles = project.resolve("core/build/classes/java/main/core/Titles.class");
        Files.writeString(build, original + "\naxl {\n    smap = false\n}\n");
        gradle(":core:compileJava").build();
        assertTrue(!new String(Files.readAllBytes(titles), java.nio.charset.StandardCharsets.ISO_8859_1).contains("*S Axolotl"));
        Files.writeString(build, original);
        gradle(":core:compileJava").build();
        assertTrue(new String(Files.readAllBytes(titles), java.nio.charset.StandardCharsets.ISO_8859_1).contains("*S Axolotl"));
    }

    @Test
    void javaConsumersOfALibrarySeeTheRuntime() throws IOException {
        Path settings = project.resolve("settings.gradle.kts");
        Files.writeString(settings, Files.readString(settings) + "\ninclude(\"javaapp\")\n");
        Path app = Files.createDirectories(project.resolve("javaapp/src/main/java/javaapp"));
        Files.writeString(project.resolve("javaapp/build.gradle.kts"),
                "plugins {\n    java\n}\n\ndependencies {\n    implementation(project(\":core\"))\n}\n");
        Files.writeString(app.resolve("Use.java"), "package javaapp;\n\nclass Use {\n    String use() {\n"
                + "        return core.Titles.title(\"x\").toString();\n    }\n}\n");
        gradle(":javaapp:compileJava").build();
    }

    @Test
    void changingAFileRegeneratesOnlyItsPackage() throws IOException {
        Path source = Files.createDirectories(project.resolve("core/src/main/axl/extra")).resolve("Extra.axl");
        Files.writeString(source, "package extra\n\nfn extra() -> Int = 1\n");
        gradle(":core:generateAxl").build();
        Path titles = project.resolve("core/build/generated/axl/main/core/Titles.java");
        Path extra = project.resolve("core/build/generated/axl/main/extra/Extra.java");
        long titlesModified = Files.getLastModifiedTime(titles).toMillis();

        Files.writeString(source, "package extra\n\nfn extra() -> Int = 2\n");
        BuildResult result = gradle(":core:generateAxl", "--info").build();

        assertTrue(result.getOutput().contains("Translated 1 .axl file(s)"), result.getOutput());
        assertEquals(titlesModified, Files.getLastModifiedTime(titles).toMillis());
        assertTrue(Files.readString(extra).contains("return 2;"));

        Files.delete(source);
        gradle(":core:generateAxl").build();
        assertTrue(Files.notExists(extra));
        assertTrue(Files.exists(titles));
    }

    @Test
    void changingAFileRetranslatesItsPackage() throws IOException {
        Path core = project.resolve("core/src/main/axl/core");
        Path greeter = core.resolve("Greeter.axl");
        Path terse = core.resolve("Terse.axl");
        Files.writeString(greeter, "package core\n\ninterface Greeter {\n    val name: String\n}\n");
        Files.writeString(terse, "package core\n\nclass Terse : Greeter {\n    val name: String = \"terse\"\n}\n");
        gradle(":core:compileJava").build();
        Path generated = project.resolve("core/build/generated/axl/main/core/Terse.java");
        assertTrue(Files.readString(generated).contains("public String name()"));

        // The unchanged interface tells what the changed class gives.
        Files.writeString(terse, "package core\n\nclass Terse : Greeter {\n    val name: String = \"brief\"\n}\n");
        BuildResult result = gradle(":core:compileJava", "--info").build();
        assertTrue(result.getOutput().contains("Translated 3 .axl file(s)"), result.getOutput());
        assertTrue(Files.readString(generated).contains("public String name()"));

        // The changed interface is checked against the unchanged class.
        Files.writeString(greeter, "package core\n\ninterface Greeter {\n    val name: String\n    fn greet() -> String\n}\n");
        BuildResult failed = gradle(":core:compileJava").buildAndFail();
        assertTrue(failed.getOutput().contains(terse + ":3:7: error: `Terse` does not give `fn greet() -> String`"),
                failed.getOutput());
    }

    @Test
    void reportsTranslationErrorsWithAxlPath() throws IOException {
        Path main = project.resolve("app/src/main/axl/app/Main.axl");
        Files.writeString(main, Files.readString(main).replace("fn main() {", "fn main() {\n    return try title(\"x\")"));
        BuildResult result = gradle(":app:compileJava").buildAndFail();
        assertTrue(result.getOutput().contains(main.toAbsolutePath() + ":6:12: error: `try` can only be used in a function that returns `Result`"), result.getOutput());
    }

    @Test
    void namesAFileThatIsNotUtf8() throws IOException {
        Path file = project.resolve("core/src/main/axl/core/Latin.axl");
        Files.write(file, new byte[] {'f', 'n', ' ', (byte) 0xff});
        BuildResult result = gradle(":core:generateAxl").buildAndFail();
        assertTrue(result.getOutput().contains(file.toAbsolutePath() + " is not UTF-8 text"), result.getOutput());
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
    void reportsJavacErrorsWithAxlPathWhenThePathHasAnEqualsSign() throws IOException {
        Path moved = project.resolve("eq=1");
        List<Path> sampleFiles;
        try (Stream<Path> files = Files.walk(project)) {
            sampleFiles = files.filter(Files::isRegularFile).toList();
        }
        for (Path file : sampleFiles) {
            Path target = moved.resolve(project.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
        }
        project = moved;
        reportsJavacErrorsWithAxlPath();
    }

    @Test
    void strictNullFlagsUncheckedJavaValues() throws IOException {
        Path build = project.resolve("app/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + "\naxl {\n    strictNull = true\n}\n");
        Path main = project.resolve("app/src/main/axl/app/Main.axl");
        Files.writeString(main, Files.readString(main).replace("println(title(\" Hello Axolotl \"))",
                "val home = System.getenv(\"HOME\")\n    println(home.length())"));
        BuildResult result = gradle(":app:compileJava").buildAndFail();
        assertTrue(result.getOutput().contains(main.toAbsolutePath() + ":7: error: `home` may be null"), result.getOutput());
    }

    @Test
    void addsSmapAndShowsAxlLinesInFailedTests() throws IOException {
        Files.writeString(project.resolve("core/src/main/axl/core/Numbers.axl"),
                "package core\n\nfn number(text: String) -> Int {\n    return Integer.parseInt(text)\n}\n"
                        + "\nclass Digits(val text: String) {\n    fn count() -> Int = text.length()\n}\n");
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
        for (String name : List.of("Numbers.class", "Numbers$Digits.class")) {
            byte[] classFile = Files.readAllBytes(project.resolve("core/build/classes/java/main/core/" + name));
            assertTrue(new String(classFile, java.nio.charset.StandardCharsets.ISO_8859_1).contains("*S Axolotl"), name);
        }
    }

    @Test
    void exceptionsOfABuiltProgramNameAxlFilesAndLines() throws IOException {
        Path main = project.resolve("app/src/main/axl/app/Main.axl");
        Files.writeString(main, "package app\n\nfn parse(text: String) -> Int = Integer.parseInt(text)\n\n"
                + "fn main() {\n    println(\"start\")\n    println(parse(\"x\"))\n}\n");
        BuildResult result = gradle(":app:run").buildAndFail();
        assertTrue(result.getOutput().contains("at app.Main.parse(Main.axl:3)"), result.getOutput());
        assertTrue(result.getOutput().contains("at app.Main.main(Main.axl:7)"), result.getOutput());
        assertTrue(!result.getOutput().contains("Main.java:"), result.getOutput());
    }

    @Test
    void withoutSmapExceptionsKeepGeneratedJavaLines() throws IOException {
        Path build = project.resolve("app/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + "\naxl {\n    smap = false\n}\n");
        Files.writeString(project.resolve("app/src/main/axl/app/Main.axl"),
                "package app\n\nfn main() {\n    println(Integer.parseInt(\"x\"))\n}\n");
        BuildResult result = gradle(":app:run").buildAndFail();
        assertTrue(result.getOutput().contains("at app.Main.main(Main.java:"), result.getOutput());
        assertTrue(!result.getOutput().contains("Main.axl:"), result.getOutput());
    }

    @Test
    void writesLanguageServerConfig() throws IOException {
        gradle(":core:axlLspConfig").build();
        String json = Files.readString(project.resolve("core/build/axl/axl.json"));
        assertTrue(json.contains(jsonString(project.resolve("core/src/main/axl"))), json);
        assertTrue(json.contains("\"strictNull\": false"), json);
        assertTrue(json.contains(jsonString(project.resolve("core/src/main/java"))), json);
        assertTrue(json.contains("axl-runtime"), json);
        assertTrue(!json.contains("generated"), json);
        String[] modules = json.split("\"sourceRoots\"");
        assertEquals(3, modules.length, json);
        assertTrue(!modules[1].contains("junit-jupiter") && !modules[1].contains(jsonPath(project.resolve("core/src/test"))), modules[1]);
        assertTrue(modules[2].contains("junit-jupiter") && modules[2].contains(jsonPath(project.resolve("core/build/classes"))),
                modules[2]);
        assertTrue(modules[2].contains("[" + jsonString(project.resolve("core/src/test/axl")) + ", "
                + jsonString(project.resolve("core/src/main/axl")) + "]"), modules[2]);
        assertTrue(modules[2].contains("\"javaSourcepath\": [" + jsonString(project.resolve("core/src/main/java")) + "]"), modules[2]);
    }

    @Test
    void writesLanguageServerConfigWithTheSourcesOfProjectDependencies() throws IOException {
        gradle(":app:axlLspConfig").build();
        String json = Files.readString(project.resolve("app/build/axl/axl.json"));
        assertTrue(json.contains("[" + jsonString(project.resolve("app/src/main/axl")) + ", "
                + jsonString(project.resolve("core/src/main/axl")) + "]"), json);
        assertTrue(json.contains("\"javaSourcepath\": [" + jsonString(project.resolve("core/src/main/java")) + "]"), json);
        assertTrue(json.contains(jsonPath(project.resolve("core/build"))), json);
    }

    @Test
    void writesLanguageServerConfigWithoutTheImplementationLibrariesOfProjectDependencies() throws IOException {
        Path build = project.resolve("core/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + """
                dependencies {
                    api(files("libs/shared.jar"))
                    implementation(files("libs/internal.jar"))
                }
                """);
        gradle(":core:axlLspConfig", ":app:axlLspConfig").build();
        String core = Files.readString(project.resolve("core/build/axl/axl.json"));
        assertTrue(core.contains("shared.jar") && core.contains("internal.jar"), core);
        String app = Files.readString(project.resolve("app/build/axl/axl.json"));
        assertTrue(app.contains("shared.jar") && !app.contains("internal.jar"), app);
    }

    @Test
    void writesLanguageServerConfigWithoutCompiling() throws IOException {
        Path titles = project.resolve("core/src/main/axl/core/Titles.axl");
        Files.writeString(titles, Files.readString(titles) + "\nfn broken() -> Int = \"x\"\n");
        BuildResult result = gradle(":core:axlLspConfig", ":app:axlLspConfig", "--configuration-cache").build();
        assertEquals(null, result.task(":core:generateAxl"));
        assertEquals(null, result.task(":core:compileJava"));
        assertTrue(Files.exists(project.resolve("app/build/axl/axl.json")));
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

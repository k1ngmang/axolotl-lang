package org.axl.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;

/** Translates every .axl file of some source trees and compiles the result together with their .java files. */
public final class AxlBuild {

    public record Result(boolean success, List<AxlDiagnostic> diagnostics, List<GeneratedSource> generated) {}

    private final List<Path> sourceDirs;
    private final Path generatedDir;
    private final Path classesDir;
    private final List<Path> classpath = new ArrayList<>(RuntimeClasspath.entries());
    private final List<String> javacOptions = new ArrayList<>();
    private Function<Path, String> display = Path::toString;
    private boolean nullChecks = true;
    private Options options = Options.DEFAULT;

    public AxlBuild(Path sourceDir, Path generatedDir, Path classesDir) {
        this(List.of(sourceDir), generatedDir, classesDir);
    }

    public AxlBuild(List<Path> sourceDirs, Path generatedDir, Path classesDir) {
        this.sourceDirs = List.copyOf(sourceDirs);
        this.generatedDir = generatedDir;
        this.classesDir = classesDir;
    }

    public AxlBuild classpath(List<Path> entries) {
        classpath.addAll(entries);
        return this;
    }

    public AxlBuild javacOptions(List<String> options) {
        javacOptions.addAll(options);
        return this;
    }

    /** Turns Error Prone and NullAway on or off; on by default. */
    public AxlBuild nullChecks(boolean enabled) {
        this.nullChecks = enabled;
        return this;
    }

    public AxlBuild options(Options options) {
        this.options = options;
        return this;
    }

    /** How file paths are shown in diagnostics. */
    public AxlBuild display(Function<Path, String> display) {
        this.display = display;
        return this;
    }

    public Result run() throws IOException {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        List<GeneratedSource> generated = new ArrayList<>();
        List<Path> axlFiles = files(".axl");
        List<Translation.Input> inputs = new ArrayList<>();
        for (Path axl : axlFiles) {
            Path root = sourceDirs.stream().filter(axl::startsWith).findFirst().orElseThrow();
            String sourcePath = root.relativize(axl).toString().replace('\\', '/');
            inputs.add(new Translation.Input(sourcePath, display.apply(axl), Files.readString(axl, StandardCharsets.UTF_8)));
        }
        List<Translator.Result> results = Translation.run(inputs, options, classpath, sourceDirs, generatedDir);
        for (int i = 0; i < results.size(); i++) {
            Translator.Result result = results.get(i);
            diagnostics.addAll(result.diagnostics());
            if (result.unit() != null) {
                Path javaFile = generatedDir.resolve(result.unit().javaPath());
                generated.add(new GeneratedSource(result.unit(), javaFile, inputs.get(i).displayPath(),
                        inputs.get(i).text().lines().toList()));
            }
        }
        if (diagnostics.stream().anyMatch(AxlDiagnostic::isError)) {
            return new Result(false, diagnostics, generated);
        }
        List<Path> sources = new ArrayList<>(files(".java"));
        generated.forEach(g -> sources.add(g.javaFile()));
        if (sources.isEmpty()) {
            return new Result(true, diagnostics, generated);
        }
        JavaCompilation.Outcome outcome = JavaCompilation.compile(sources, classpath, classesDir, javacOptions, nullChecks);
        var translator = new DiagnosticTranslator(generated, display);
        for (var d : outcome.diagnostics()) {
            AxlDiagnostic translated = translator.translate(d);
            if (translated != null) {
                diagnostics.add(translated);
            }
        }
        return new Result(outcome.success(), diagnostics, generated);
    }

    private List<Path> files(String extension) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path dir : sourceDirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> p.toString().endsWith(extension) && Files.isRegularFile(p)).sorted().forEach(result::add);
                }
            }
        }
        return result;
    }
}

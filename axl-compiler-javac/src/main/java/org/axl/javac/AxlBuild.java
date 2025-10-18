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
import org.axl.compiler.Translator;

/** Translates every .axl file of a source tree and compiles the result together with its .java files. */
public final class AxlBuild {

    public record Result(boolean success, List<AxlDiagnostic> diagnostics, List<GeneratedSource> generated) {}

    private final Path sourceDir;
    private final Path generatedDir;
    private final Path classesDir;
    private final List<Path> classpath = new ArrayList<>(RuntimeClasspath.entries());
    private final List<String> javacOptions = new ArrayList<>();
    private Function<Path, String> display = Path::toString;

    public AxlBuild(Path sourceDir, Path generatedDir, Path classesDir) {
        this.sourceDir = sourceDir;
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

    /** How file paths are shown in diagnostics. */
    public AxlBuild display(Function<Path, String> display) {
        this.display = display;
        return this;
    }

    public Result run() throws IOException {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        List<GeneratedSource> generated = new ArrayList<>();
        for (Path axl : files(".axl")) {
            String sourcePath = sourceDir.relativize(axl).toString().replace('\\', '/');
            String text = Files.readString(axl, StandardCharsets.UTF_8);
            Translator.Result result = Translator.translate(sourcePath, display.apply(axl), text);
            diagnostics.addAll(result.diagnostics());
            if (result.unit() != null) {
                Path javaFile = result.unit().writeTo(generatedDir);
                generated.add(new GeneratedSource(result.unit(), javaFile, display.apply(axl), text.lines().toList()));
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
        JavaCompilation.Outcome outcome = JavaCompilation.compile(sources, classpath, classesDir, javacOptions);
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
        if (!Files.isDirectory(sourceDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk.filter(p -> p.toString().endsWith(extension) && Files.isRegularFile(p)).sorted().toList();
        }
    }
}

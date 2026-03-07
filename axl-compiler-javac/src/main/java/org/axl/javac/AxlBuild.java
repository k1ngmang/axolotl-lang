package org.axl.javac;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
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
    private Predicate<Path> skipped = file -> false;
    private boolean nullChecks = true;
    private boolean strictNull;
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

    /** Treats values from Java code without nullness annotations as nullable. */
    public AxlBuild strictNull(boolean enabled) {
        this.strictNull = enabled;
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

    /** Files under the source directories that are not sources, such as those of other builds. */
    public AxlBuild skip(Predicate<Path> files) {
        this.skipped = files;
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
            inputs.add(new Translation.Input(sourcePath, display.apply(axl), read(axl)));
        }
        List<Translator.Result> results = Translation.run(inputs, options, classpath, sourceDirs, generatedDir, strictNull);
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
            return new Result(false, sorted(diagnostics), generated);
        }
        List<Path> sources = new ArrayList<>(files(".java"));
        generated.forEach(g -> sources.add(g.javaFile()));
        if (sources.isEmpty()) {
            return new Result(true, diagnostics, generated);
        }
        JavaCompilation.Outcome outcome = JavaCompilation.compile(sources, classpath, classesDir, javacOptions, nullChecks, strictNull);
        var translator = new DiagnosticTranslator(generated, display);
        for (var d : outcome.diagnostics()) {
            AxlDiagnostic translated = translator.translate(d);
            if (translated != null && !repeated(diagnostics, translated)) {
                diagnostics.add(translated);
            }
        }
        return new Result(outcome.success(), sorted(diagnostics), generated);
    }

    private String read(Path axl) throws IOException {
        try {
            return Files.readString(axl, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException(display.apply(axl) + " is not UTF-8 text", e);
        }
    }

    /** One .axl line becomes several Java lines, which can repeat the same error. */
    private static boolean repeated(List<AxlDiagnostic> diagnostics, AxlDiagnostic diagnostic) {
        if (!Messages.oncePerLine(diagnostic.message())) {
            return diagnostics.contains(diagnostic);
        }
        return diagnostics.stream().anyMatch(d -> d.file().equals(diagnostic.file()) && d.line() == diagnostic.line()
                && d.message().equals(diagnostic.message()));
    }

    private static List<AxlDiagnostic> sorted(List<AxlDiagnostic> diagnostics) {
        diagnostics.sort(Comparator.comparing(AxlDiagnostic::file).thenComparingInt(AxlDiagnostic::line)
                .thenComparingInt(AxlDiagnostic::column));
        return diagnostics;
    }

    /**
     * Source files, each once even when source directories overlap. Output directories inside a source
     * directory, as with `-d out` there, are skipped; with the output in the source directory itself,
     * as with `-d .`, the translated .java files are.
     */
    private List<Path> files(String extension) throws IOException {
        List<Path> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path dir : sourceDirs) {
            if (Files.isDirectory(dir)) {
                List<Path> outputs = outputsInside(dir);
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> p.toString().endsWith(extension) && Files.isRegularFile(p))
                            .filter(p -> outputs.stream().noneMatch(p.toAbsolutePath().normalize()::startsWith))
                            .filter(p -> !isTranslated(p) && !skipped.test(p))
                            .filter(p -> seen.add(p.toAbsolutePath().normalize()))
                            .sorted().forEach(result::add);
                }
            }
        }
        return result;
    }

    /** The output directories strictly inside a source directory. */
    private List<Path> outputsInside(Path sourceDir) {
        Path root = sourceDir.toAbsolutePath().normalize();
        return Stream.of(generatedDir, classesDir).map(p -> p.toAbsolutePath().normalize())
                .filter(p -> p.startsWith(root) && !p.equals(root))
                .toList();
    }

    /** A .java file with an .axlmap next to it was translated from an .axl file. */
    private static boolean isTranslated(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java")
                && Files.exists(file.resolveSibling(name.substring(0, name.length() - ".java".length()) + ".axlmap"));
    }
}

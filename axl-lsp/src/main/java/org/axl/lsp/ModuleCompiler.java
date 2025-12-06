package org.axl.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.GeneratedUnit;
import org.axl.javac.DiagnosticTranslator;
import org.axl.javac.GeneratedSource;
import org.axl.javac.JavaCompilation;
import org.axl.javac.RuntimeClasspath;

/**
 * Compiles a whole module with javac, Error Prone and NullAway, as the build does, and returns the
 * errors of every .axl file. Runs when a file is saved.
 */
final class ModuleCompiler {

    private final Path work;
    private boolean nullChecks = true;

    ModuleCompiler() throws IOException {
        this.work = Files.createTempDirectory("axl-lsp");
    }

    /** documents: every .axl file of the module, by URI, already translated. */
    Map<String, List<AxlDiagnostic>> compile(Module module, List<Document> documents) throws IOException {
        Path generatedDir = clean(work.resolve("gen"));
        Path classes = clean(work.resolve("classes"));
        List<GeneratedSource> generated = new ArrayList<>();
        Map<String, List<AxlDiagnostic>> result = new HashMap<>();
        Map<String, List<Document>> byClass = new LinkedHashMap<>();
        for (Document document : documents) {
            result.put(document.uri, new ArrayList<>());
            GeneratedUnit unit = document.translation.unit();
            if (unit != null) {
                byClass.computeIfAbsent(unit.qualifiedName(), k -> new ArrayList<>()).add(document);
            }
        }
        for (var entry : byClass.entrySet()) {
            List<Document> same = entry.getValue();
            if (same.size() > 1) {
                // Their Java would overwrite each other's, so none of them is compiled.
                String files = same.stream().map(d -> d.sourcePath).collect(Collectors.joining(", "));
                for (Document document : same) {
                    result.get(document.uri).add(AxlDiagnostic.error(document.uri, 1, 1, ErrorCode.DUPLICATE_CLASS,
                            "the class `" + entry.getKey() + "` is declared by several files: " + files));
                }
                continue;
            }
            Document document = same.getFirst();
            GeneratedUnit unit = document.translation.unit();
            generated.add(new GeneratedSource(unit, unit.writeTo(generatedDir), document.uri, document.lines));
        }
        List<Path> sources = new ArrayList<>(javaFiles(module));
        generated.forEach(g -> sources.add(g.javaFile()));
        if (sources.isEmpty()) {
            return result;
        }
        List<Path> classpath = new ArrayList<>(RuntimeClasspath.entries());
        classpath.addAll(module.classpath());
        JavaCompilation.Outcome outcome;
        try {
            outcome = JavaCompilation.compile(sources, classpath, classes, List.of(), nullChecks, module.strictNull());
        } catch (IllegalStateException e) {
            // Started without the JVM options Error Prone needs: plain javac still finds type errors.
            nullChecks = false;
            outcome = JavaCompilation.compile(sources, classpath, classes, List.of(), false, module.strictNull());
        }
        var translator = new DiagnosticTranslator(generated, Path::toString);
        for (var d : outcome.diagnostics()) {
            AxlDiagnostic translated = translator.translate(d);
            if (translated != null && result.containsKey(translated.file())) {
                result.get(translated.file()).add(translated);
            }
        }
        return result;
    }

    private static Path clean(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        return Files.createDirectories(dir);
    }

    private static List<Path> javaFiles(Module module) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : module.javaRoots()) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(result::add);
                }
            }
        }
        return result;
    }
}

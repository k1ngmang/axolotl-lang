package org.axl.javac;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs javac in process with `--release 21`. */
public final class JavaCompilation {

    public record Outcome(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    private JavaCompilation() {}

    public static Outcome compile(List<Path> sources, List<Path> classpath, Path classesDir, List<String> extraOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no Java compiler available: run with a JDK, not a JRE");
        }
        var collector = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Files.createDirectories(classesDir);
            List<String> options = new ArrayList<>(List.of(
                    "--release", "21",
                    "-encoding", "UTF-8",
                    "-proc:none",
                    "-d", classesDir.toString(),
                    "-classpath", classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator))));
            options.addAll(extraOptions);
            var units = files.getJavaFileObjectsFromPaths(sources);
            boolean success = compiler.getTask(null, files, collector, options, null, units).call();
            return new Outcome(success, collector.getDiagnostics());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

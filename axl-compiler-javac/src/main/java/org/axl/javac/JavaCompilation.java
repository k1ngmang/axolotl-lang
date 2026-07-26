package org.axl.javac;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import com.sun.source.util.JavacTask;
import java.util.stream.Stream;
import org.axl.compiler.LargeStack;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs javac in process with `--release 21`, optionally with Error Prone and NullAway in JSpecify mode.
 * Error Prone needs the JVM flags listed in {@link #ERROR_PRONE_JVM_ARGS}.
 */
public final class JavaCompilation {

    public static final List<String> ERROR_PRONE_JVM_ARGS = List.of("api", "code", "comp", "file", "main", "model",
                    "parser", "processing", "tree", "util").stream()
            .flatMap(p -> Stream.of("--add-exports=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED"))
            .toList();

    /**
     * The plugin argument; NullAway only checks classes annotated with {@code @NullMarked}. `f() == f()`
     * is only a warning, since the two calls can give different values, and `import fn` is meant to
     * import a static method by its plain name, which BadImport would flag. InlineMeSuggester asks for
     * an annotation on every deprecated function. `==` on strings and other values with their own
     * `equals` is an error: the program compiles but compares the wrong thing. A call that always fails is a warning here and made
     * an error again when it is not inside `attempt`, which handles the failure (see Messages).
     * AlreadyChecked crashes on the `var` of a record pattern, `x instanceof Ok(var v)`, inside an `if`.
     */
    public static final String ERROR_PRONE_PLUGIN = "-Xplugin:ErrorProne -Xep:NullAway:ERROR -Xep:NarrowingCompoundAssignment:ERROR "
            + "-Xep:ReferenceEquality:ERROR -Xep:IdentityBinaryExpression:WARN -Xep:AlwaysThrows:WARN -Xep:AlreadyChecked:OFF -Xep:BadImport:OFF "
            + "-Xep:InlineMeSuggester:OFF -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true";

    public record Outcome(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    private JavaCompilation() {}

    public static Outcome compile(List<Path> sources, List<Path> classpath, Path classesDir, List<String> extraOptions,
                                  boolean nullChecks) {
        return compile(sources, classpath, classesDir, extraOptions, nullChecks, false);
    }

    /** strictNull: values from Java code without nullness annotations may be null (see NullChecker). */
    public static Outcome compile(List<Path> sources, List<Path> classpath, Path classesDir, List<String> extraOptions,
                                  boolean nullChecks, boolean strictNull) {
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
            if (nullChecks) {
                requireCompilerAccess();
                options.addAll(List.of(
                        "-XDcompilePolicy=simple",
                        "-XDaddTypeAnnotationsToSymbol=true",
                        "--should-stop=ifError=FLOW",
                        "-processorpath", processorPath(),
                        ERROR_PRONE_PLUGIN));
            }
            if (strictNull && !nullChecks) {
                options.addAll(List.of("-processorpath", processorPath()));
            }
            if (nullChecks || strictNull) {
                options.add("-Xplugin:" + DiagnosticsPlugin.NAME + (strictNull ? " " + DiagnosticsPlugin.STRICT : ""));
            }
            options.addAll(extraOptions);
            var units = files.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, files, collector, options, null, units);
            // javac recurses once per nesting level too.
            boolean success = LargeStack.call(task::call, () -> false);
            // The file manager reports a source that is not UTF-8 as an error, but the task still succeeds.
            boolean errors = collector.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            return new Outcome(success && !errors, collector.getDiagnostics());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Error Prone and NullAway are loaded from the classpath this class came from: the application
     * classpath, or the plugin class loader when running inside a build tool such as Maven.
     */
    private static String processorPath() {
        if (JavaCompilation.class.getClassLoader() instanceof URLClassLoader loader) {
            // Maven keeps the jars it provides itself out of a plugin's class loader; Error Prone needs javax.inject.
            Stream<String> provided = jarOf("javax.inject.Inject", loader).stream();
            return Stream.concat(Arrays.stream(loader.getURLs()).map(JavaCompilation::toPath), provided).distinct()
                    .collect(Collectors.joining(java.io.File.pathSeparator));
        }
        return System.getProperty("java.class.path");
    }

    private static Optional<String> jarOf(String className, ClassLoader loader) {
        try {
            URL location = Class.forName(className, false, loader).getProtectionDomain().getCodeSource().getLocation();
            return Optional.of(toPath(location));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private static String toPath(URL url) {
        try {
            return Path.of(url.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireCompilerAccess() {
        boolean exported = ModuleLayer.boot().findModule("jdk.compiler")
                .map(m -> m.isOpen("com.sun.tools.javac.comp", JavaCompilation.class.getModule()))
                .orElse(false);
        if (!exported) {
            throw new IllegalStateException("Error Prone needs these JVM options: " + String.join(" ", ERROR_PRONE_JVM_ARGS));
        }
    }
}

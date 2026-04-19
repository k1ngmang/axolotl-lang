package org.axl.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.tools.javac.api.BasicJavacTask;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * javac plugin that reports errors in generated .java files at their .axl lines.
 * Build tools enable it with {@code -Xplugin:AxlDiagnostics <generated-dir>=<axl-dir> ...}. Directories are
 * file URIs, so that paths with spaces survive javac's argument splitting, or plain paths. The plugin
 * also makes the null checks NullAway cannot make; the argument {@code strict} turns on strict null
 * mode (see NullChecker).
 */
public final class DiagnosticsPlugin implements Plugin {

    public static final String NAME = "AxlDiagnostics";
    public static final String STRICT = "strict";

    /** A directory of generated Java and the .axl source directory it was generated from. */
    public record Root(Path generated, Path sources) {

        /** File URIs do not escape `=`, which separates the two directories. */
        public String argument() {
            return uri(generated) + "=" + uri(sources);
        }

        private static String uri(Path dir) {
            return dir.toUri().toString().replace("=", "%3D");
        }

        static Root parse(String argument) {
            int eq = argument.indexOf('=');
            return new Root(path(argument.substring(0, eq)), path(argument.substring(eq + 1)));
        }

        private static Path path(String text) {
            return text.startsWith("file:") ? Path.of(URI.create(text)) : Path.of(text);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        List<Root> roots = new ArrayList<>();
        boolean strict = false;
        for (String arg : args) {
            if (arg.equals(STRICT)) {
                strict = true;
            } else {
                roots.add(Root.parse(arg));
            }
        }
        if (!roots.isEmpty()) {
            new AxlDiagnosticHandler(((BasicJavacTask) task).getContext(), roots);
        }
        NullChecker nullChecker = new NullChecker(task, strict, nullAwayReports(task));
        InitCycles initCycles = new InitCycles(task);
        task.addTaskListener(nullChecker);
        task.addTaskListener(initCycles);
        task.addTaskListener(new AnalysisEnd(task, () -> {
            initCycles.report();
            nullChecker.report();
        }));
    }

    /**
     * Installed after the handler that moves diagnostics to .axl files, so that it sees them first,
     * at their Java positions. Null without access to javac's internals, which Error Prone needs too.
     */
    private static NullAwayReports nullAwayReports(JavacTask task) {
        boolean internals = ModuleLayer.boot().findModule("jdk.compiler")
                .map(m -> m.isExported("com.sun.tools.javac.util", DiagnosticsPlugin.class.getModule()))
                .orElse(false);
        return internals && task instanceof BasicJavacTask basic ? new NullAwayReports(basic.getContext()) : null;
    }

    public static String option(List<Root> roots) {
        StringBuilder sb = new StringBuilder("-Xplugin:" + NAME);
        roots.forEach(r -> sb.append(' ').append(r.argument()));
        return sb.toString();
    }
}

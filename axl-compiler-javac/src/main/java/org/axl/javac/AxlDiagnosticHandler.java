package org.axl.javac;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticType;
import com.sun.tools.javac.util.JCDiagnostic.SimpleDiagnosticPosition;
import com.sun.tools.javac.util.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.axl.compiler.LineMap;

/** Replaces errors and warnings in generated files with the same message at the .axl position. */
final class AxlDiagnosticHandler extends Log.DiagnosticHandler {

    /** A generated file whose diagnostics can be moved, or null when the file is not generated. */
    private record Mapping(LineMap lineMap, String[] javaLines, AxlFile axl) {}

    private static final class AxlFile extends SimpleJavaFileObject {
        final String text;
        final List<String> lines;
        private final Path path;

        AxlFile(Path path, String text) {
            super(path.toUri(), Kind.OTHER);
            this.path = path;
            // Lines as the translator counts them: a lone \r ends a line too.
            this.text = text.replaceFirst("^\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
            this.lines = this.text.lines().toList();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }

        /** javac prints this name; the URI path would be `/C:/...` on Windows. */
        @Override
        public String getName() {
            return path.toString();
        }

        int offset(int line, int column) {
            int offset = 0;
            for (int i = 0; i < line - 1 && i < lines.size(); i++) {
                offset = text.indexOf('\n', offset) + 1;
            }
            int lineEnd = text.indexOf('\n', offset) < 0 ? text.length() : text.indexOf('\n', offset);
            // Columns count code points; an offset counts chars.
            return text.offsetByCodePoints(offset, Math.min(column - 1, text.codePointCount(offset, lineEnd)));
        }
    }

    private static final Pattern JAVA_FILE = Pattern.compile("\\S+\\.java\\b");

    private final Log log;
    private final JCDiagnostic.Factory factory;
    private final List<DiagnosticsPlugin.Root> roots;
    private final Map<Path, Mapping> mappings = new HashMap<>();
    private final Set<String> reported = new HashSet<>();

    AxlDiagnosticHandler(Context context, List<DiagnosticsPlugin.Root> roots) {
        this.log = Log.instance(context);
        this.factory = JCDiagnostic.Factory.instance(context);
        this.roots = roots;
        install(log);
    }

    @Override
    public void report(JCDiagnostic original) {
        if (original.getType() == DiagnosticType.NOTE) {
            prev.report(withAxlPaths(original));
            return;
        }
        JCDiagnostic diagnostic = rewritten(original);
        JCDiagnostic moved = move(diagnostic);
        if (moved == null) {
            prev.report(diagnostic);
        } else if (ignored(diagnostic)) {
            return;
        } else if (reported.add(moved.getSource().toUri() + ":" + moved.getLineNumber() + ":" + moved.getMessage(Locale.ROOT))) {
            // One .axl line becomes several Java lines, which can repeat the same error.
            prev.report(moved);
        }
    }

    /**
     * javac's own handler first replaces some errors with simpler ones, such as a wrong lambda result
     * for "no suitable method"; a handler installed before it has to do that itself. The methods are
     * not public, and their absence only costs the simpler wording.
     */
    private static JCDiagnostic rewritten(JCDiagnostic diagnostic) {
        try {
            Method hasRewriter = JCDiagnostic.class.getDeclaredMethod("hasRewriter");
            hasRewriter.setAccessible(true);
            if (!(boolean) hasRewriter.invoke(diagnostic)) {
                return diagnostic;
            }
            Method rewrite = JCDiagnostic.class.getDeclaredMethod("rewrite");
            rewrite.setAccessible(true);
            JCDiagnostic result = (JCDiagnostic) rewrite.invoke(diagnostic);
            return result == null ? diagnostic : result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return diagnostic;
        }
    }

    /** Notes such as "X.java uses unchecked or unsafe operations" name the .axl file instead. */
    private JCDiagnostic withAxlPaths(JCDiagnostic note) {
        String text = note.getMessage(Locale.ROOT);
        Matcher javaFile = JAVA_FILE.matcher(text);
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        while (javaFile.find()) {
            Mapping mapping = mapping(Path.of(javaFile.group()).toAbsolutePath().normalize());
            String replacement = mapping == null ? javaFile.group() : Path.of(mapping.axl().toUri()).toString();
            changed |= mapping != null;
            javaFile.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        javaFile.appendTail(result);
        return changed ? factory.create(DiagnosticType.NOTE, DiagnosticSource.NO_SOURCE, null, "proc.messager",
                result.toString()) : note;
    }

    private JCDiagnostic move(JCDiagnostic diagnostic) {
        DiagnosticType type = rawType(diagnostic) ? DiagnosticType.ERROR : diagnostic.getType();
        if (type != DiagnosticType.ERROR && type != DiagnosticType.WARNING || diagnostic.getSource() == null
                || !"file".equals(diagnostic.getSource().toUri().getScheme())) {
            return null;
        }
        Mapping mapping = mapping(Path.of(diagnostic.getSource().toUri()).toAbsolutePath().normalize());
        if (mapping == null) {
            return null;
        }
        int javaLine = (int) diagnostic.getLineNumber();
        int axlLine = Math.max(1, mapping.lineMap().nearestAxlLine(javaLine));
        String message = DiagnosticTranslator.oneLine(diagnostic.getMessage(Locale.ROOT));
        String translated = Messages.translate(message, mapping.javaLines(), javaLine).message();
        AxlFile axl = mapping.axl();
        String axlText = axlLine <= axl.lines.size() ? axl.lines.get(axlLine - 1) : "";
        String javaText = javaLine >= 1 && javaLine <= mapping.javaLines().length ? mapping.javaLines()[javaLine - 1] : "";
        int column = Columns.find(mapping.javaLines(), mapping.lineMap(), javaLine, Columns.javaColumn(mapping.javaLines(), javaLine, diagnostic),
                axlText, Messages.placement(translated, javaText));
        JCDiagnostic moved = factory.create(type, new DiagnosticSource(axl, log),
                new SimpleDiagnosticPosition(axl.offset(axlLine, column)), "proc.messager", translated);
        // javac shows one error per position, and many .axl errors share one; `report` drops true repeats.
        moved.setFlag(JCDiagnostic.DiagnosticFlag.API);
        return moved;
    }

    /** See `Messages.ignored`; the diagnostic is in a generated file. */
    private boolean ignored(JCDiagnostic diagnostic) {
        Mapping mapping = mapping(Path.of(diagnostic.getSource().toUri()).toAbsolutePath().normalize());
        int line = (int) diagnostic.getLineNumber();
        String javaText = line >= 1 && line <= mapping.javaLines().length ? mapping.javaLines()[line - 1] : "";
        return Messages.ignored(DiagnosticTranslator.oneLine(diagnostic.getMessage(Locale.ROOT)), javaText, mapping.axl().text);
    }

    private static boolean rawType(JCDiagnostic diagnostic) {
        return diagnostic.getType() == DiagnosticType.WARNING && diagnostic.getCode().endsWith("raw.class.use");
    }

    private Mapping mapping(Path javaFile) {
        return mappings.computeIfAbsent(javaFile, this::load);
    }

    private Mapping load(Path javaFile) {
        for (DiagnosticsPlugin.Root root : roots) {
            Path generated = root.generated().toAbsolutePath().normalize();
            String name = javaFile.getFileName().toString();
            if (!javaFile.startsWith(generated) || !name.endsWith(".java")) {
                continue;
            }
            Path mapFile = javaFile.resolveSibling(name.substring(0, name.length() - ".java".length()) + ".axlmap");
            if (!Files.exists(mapFile)) {
                return null;
            }
            try {
                LineMap lineMap = LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8));
                // Several source directories can share one generated directory.
                Path axlPath = root.sources().resolve(lineMap.source());
                if (!Files.exists(axlPath)) {
                    continue;
                }
                String axlText = Files.readString(axlPath, StandardCharsets.UTF_8);
                String[] javaLines = Files.readString(javaFile, StandardCharsets.UTF_8).split("\n", -1);
                return new Mapping(lineMap, javaLines, new AxlFile(axlPath, axlText));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }
}

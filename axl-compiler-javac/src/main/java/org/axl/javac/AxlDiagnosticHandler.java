package org.axl.javac;

import com.sun.tools.javac.api.DiagnosticFormatter;
import com.sun.tools.javac.util.BasicDiagnosticFormatter;
import com.sun.tools.javac.util.BasicDiagnosticFormatter.BasicConfiguration.BasicFormatKind;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ForwardingDiagnosticFormatter;
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

        /** javac counts a tab up to the next multiple of 8 in a column; Axolotl's columns count it as one character. */
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text.replace('\t', ' ');
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
    private final Set<String> javaPositions = new HashSet<>();
    // The Error Prone checks whose first warning has named them.
    private final Set<String> namedChecks = new HashSet<>();

    AxlDiagnosticHandler(Context context, List<DiagnosticsPlugin.Root> roots) {
        this.log = Log.instance(context);
        this.factory = JCDiagnostic.Factory.instance(context);
        this.roots = roots;
        install(log);
        withColumns(log);
    }

    /**
     * Gradle prints each diagnostic with javac's formatter, whose `file:line:` has no column; the `axl` command
     * prints `file:line:column:`.
     */
    private static void withColumns(Log log) {
        DiagnosticFormatter<JCDiagnostic> formatter = log.getDiagnosticFormatter();
        if (formatter instanceof ForwardingDiagnosticFormatter<JCDiagnostic, ?> forwarding) {
            formatter = forwarding.getDelegatedFormatter();
        }
        if (formatter instanceof BasicDiagnosticFormatter basic) {
            basic.getConfiguration().setFormat(BasicFormatKind.DEFAULT_POS_FORMAT, "%f:%l:%c:%_%p%L%m");
        }
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
        } else if (!firstAtJavaPosition(diagnostic) || ignored(diagnostic)) {
            return;
        } else if (reported.add(moved.getSource().toUri() + ":" + moved.getLineNumber() + ":"
                + ErrorProneMessages.withoutSuppression(moved.getMessage(Locale.ROOT)))) {
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
        DiagnosticType type = diagnostic.getType();
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
        long javaColumn = Columns.javaColumn(mapping.javaLines(), javaLine, diagnostic);
        AxlFile axl = mapping.axl();
        String axlText = axlLine <= axl.lines.size() ? axl.lines.get(axlLine - 1) : "";
        Messages.Translated result = Messages.translate(message, mapping.javaLines(), javaLine, javaColumn, axlText);
        String translated = result.message();
        String check = type == DiagnosticType.WARNING ? ErrorProneMessages.check(message) : null;
        if (check != null && namedChecks.add(check)) {
            translated += ErrorProneMessages.suppression(check);
        }
        String javaText = javaLine >= 1 && javaLine <= mapping.javaLines().length ? mapping.javaLines()[javaLine - 1] : "";
        int column = Columns.find(mapping.javaLines(), mapping.lineMap(), javaLine, javaColumn, axlText,
                Messages.placement(translated, javaText));
        DiagnosticType severity = Messages.alwaysFailsUnhandled(message, javaText) ? DiagnosticType.ERROR : type;
        // The code ends the message, as the `axl` command prints it, so that docs/errors.md can be looked up.
        JCDiagnostic moved = factory.create(severity, new DiagnosticSource(axl, log),
                new SimpleDiagnosticPosition(axl.offset(axlLine, column)), "proc.messager",
                translated + " [" + result.code().id() + "]");
        // javac shows one error per position, and many .axl errors share one; `report` drops true repeats.
        moved.setFlag(JCDiagnostic.DiagnosticFlag.API);
        return moved;
    }

    /**
     * javac reports one error per position, such as one of the checked exceptions a call throws, unless the error
     * comes from a plugin. A moved error skips that check, so it is made here on the generated file.
     */
    private boolean firstAtJavaPosition(JCDiagnostic diagnostic) {
        return diagnostic.getType() != DiagnosticType.ERROR || diagnostic.isFlagSet(JCDiagnostic.DiagnosticFlag.API)
                || javaPositions.add(diagnostic.getSource().toUri() + ":" + diagnostic.getPosition());
    }

    /** See `Messages.ignored`; the diagnostic is in a generated file. */
    private boolean ignored(JCDiagnostic diagnostic) {
        Mapping mapping = mapping(Path.of(diagnostic.getSource().toUri()).toAbsolutePath().normalize());
        int line = (int) diagnostic.getLineNumber();
        String javaText = line >= 1 && line <= mapping.javaLines().length ? mapping.javaLines()[line - 1] : "";
        return Messages.ignored(DiagnosticTranslator.oneLine(diagnostic.getMessage(Locale.ROOT)), javaText, mapping.axl().text,
                Columns.javaColumn(mapping.javaLines(), line, diagnostic));
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

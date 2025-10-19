package org.axl.javac;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticType;
import com.sun.tools.javac.util.JCDiagnostic.SimpleDiagnosticPosition;
import com.sun.tools.javac.util.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        AxlFile(Path path, String text) {
            super(path.toUri(), Kind.OTHER);
            this.text = text;
            this.lines = text.lines().toList();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }

        int offset(int line, int column) {
            int offset = 0;
            for (int i = 0; i < line - 1 && i < lines.size(); i++) {
                offset = text.indexOf('\n', offset) + 1;
            }
            return offset + column - 1;
        }
    }

    private final Log log;
    private final JCDiagnostic.Factory factory;
    private final List<DiagnosticsPlugin.Root> roots;
    private final Map<Path, Mapping> mappings = new HashMap<>();

    AxlDiagnosticHandler(Context context, List<DiagnosticsPlugin.Root> roots) {
        this.log = Log.instance(context);
        this.factory = JCDiagnostic.Factory.instance(context);
        this.roots = roots;
        install(log);
    }

    @Override
    public void report(JCDiagnostic diagnostic) {
        JCDiagnostic moved = move(diagnostic);
        prev.report(moved != null ? moved : diagnostic);
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
        String translated = Messages.translate(message, mapping.javaLines(), javaLine).message();
        AxlFile axl = mapping.axl();
        int column = firstColumn(axl.lines, axlLine);
        return factory.create(type, new DiagnosticSource(axl, log),
                new SimpleDiagnosticPosition(axl.offset(axlLine, column)), "proc.messager", translated);
    }

    private static int firstColumn(List<String> lines, int line) {
        if (line > lines.size()) {
            return 1;
        }
        String text = lines.get(line - 1);
        int column = 0;
        while (column < text.length() && Character.isWhitespace(text.charAt(column))) {
            column++;
        }
        return column + 1;
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
                Path axlPath = root.sources().resolve(lineMap.source());
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

package org.axl.javac;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;

/** Moves javac diagnostics from generated .java files to the .axl lines they came from. */
public final class DiagnosticTranslator {

    private final Map<Path, GeneratedSource> byJavaFile = new HashMap<>();
    private final Function<Path, String> javaDisplay;

    public DiagnosticTranslator(List<GeneratedSource> sources, Function<Path, String> javaDisplay) {
        for (GeneratedSource source : sources) {
            byJavaFile.put(normalize(source.javaFile()), source);
        }
        this.javaDisplay = javaDisplay;
    }

    /** Returns null for notes, which are not worth showing. */
    public AxlDiagnostic translate(Diagnostic<? extends JavaFileObject> diagnostic) {
        AxlDiagnostic.Severity severity = switch (diagnostic.getKind()) {
            case ERROR -> AxlDiagnostic.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> AxlDiagnostic.Severity.WARNING;
            default -> null;
        };
        if (severity == null) {
            return null;
        }
        String message = oneLine(diagnostic.getMessage(Locale.ROOT));
        if (diagnostic.getSource() == null) {
            return new AxlDiagnostic("javac", 0, 0, severity, ErrorCode.JAVAC, message);
        }
        Path javaFile = normalize(Path.of(diagnostic.getSource().toUri()));
        int javaLine = (int) diagnostic.getLineNumber();
        GeneratedSource source = byJavaFile.get(javaFile);
        if (source == null) {
            return new AxlDiagnostic(javaDisplay.apply(javaFile), javaLine, (int) diagnostic.getColumnNumber(),
                    severity, ErrorCode.JAVAC, message);
        }
        int axlLine = source.unit().lineMap().nearestAxlLine(javaLine);
        String[] javaLines = source.unit().javaSource().split("\n", -1);
        Messages.Translated translated = Messages.translate(message, javaLines, javaLine);
        return new AxlDiagnostic(source.displayPath(), Math.max(axlLine, 1), source.firstColumn(axlLine),
                severity, translated.code(), translated.message());
    }

    /** javac puts symbol and location details on extra lines. */
    private static String oneLine(String message) {
        return message.lines().map(l -> l.strip().replaceAll("\\s+", " ")).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("; "));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

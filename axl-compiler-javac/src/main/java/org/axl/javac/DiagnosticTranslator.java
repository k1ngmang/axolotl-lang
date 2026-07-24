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
    private final OtherFiles otherFiles;

    public DiagnosticTranslator(List<GeneratedSource> sources, Function<Path, String> javaDisplay) {
        for (GeneratedSource source : sources) {
            byJavaFile.put(normalize(source.javaFile()), source);
        }
        this.javaDisplay = javaDisplay;
        this.otherFiles = new OtherFiles(sources);
    }

    /** Returns null for notes and for warnings about the shape of generated code. */
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
            return Messages.ignored(message) ? null : new AxlDiagnostic("javac", 0, 0, severity, ErrorCode.JAVAC, message);
        }
        Path javaFile = normalize(Path.of(diagnostic.getSource().toUri()));
        int javaLine = (int) diagnostic.getLineNumber();
        GeneratedSource source = byJavaFile.get(javaFile);
        if (source == null) {
            // Hand-written Java gets every warning; only generated code has a shape of its own.
            return new AxlDiagnostic(javaDisplay.apply(javaFile), javaLine, (int) diagnostic.getColumnNumber(),
                    Messages.alwaysFailsUnhandled(message, "") ? AxlDiagnostic.Severity.ERROR : severity, ErrorCode.JAVAC, message);
        }
        String[] javaLines = source.unit().javaSource().split("\n", -1);
        String javaText = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        long javaColumn = Columns.javaColumn(javaLines, javaLine, diagnostic);
        if (Messages.ignored(message, javaText, String.join("\n", source.axlLines()), javaColumn)) {
            return null;
        }
        if (Messages.alwaysFailsUnhandled(message, javaText)) {
            severity = AxlDiagnostic.Severity.ERROR;
        }
        Messages.Translated translated = Messages.translate(message, javaLines, javaLine, javaColumn);
        int reportedLine = Messages.javaLine(translated.message(), javaLines, javaLine);
        if (reportedLine != javaLine) {
            javaLine = reportedLine;
            javaText = javaLines[javaLine - 1];
            javaColumn = Columns.javaColumn(javaLines, javaLine, diagnostic);
        }
        int axlLine = source.unit().lineMap().nearestAxlLine(javaLine);
        int line = Math.max(axlLine, 1);
        String axlText = line <= source.axlLines().size() ? source.axlLines().get(line - 1) : "";
        int column = Columns.find(javaLines, source.unit().lineMap(), javaLine, javaColumn,
                axlText, Messages.placement(translated.message(), javaText));
        return new AxlDiagnostic(source.displayPath(), line, column, severity, translated.code(),
                otherFiles.reword(translated.message()));
    }

    /** javac puts symbol and location details on extra lines. */
    static String oneLine(String message) {
        return message.lines().map(l -> l.strip().replaceAll("\\s+", " ")).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("; "));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

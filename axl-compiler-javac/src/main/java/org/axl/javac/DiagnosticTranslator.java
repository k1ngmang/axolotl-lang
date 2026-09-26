package org.axl.javac;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    // The Error Prone check behind each warning of generated code.
    private final Map<AxlDiagnostic, String> checks = new HashMap<>();

    public DiagnosticTranslator(List<GeneratedSource> sources, Function<Path, String> javaDisplay) {
        for (GeneratedSource source : sources) {
            byJavaFile.put(normalize(source.javaFile()), source);
        }
        this.javaDisplay = javaDisplay;
        this.otherFiles = new OtherFiles(sources);
    }

    /** The classpath of the compilation, whose classes an unknown name may need an import of. */
    public DiagnosticTranslator classpath(List<Path> entries) {
        otherFiles.classpath(entries);
        return this;
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
                    Messages.alwaysFailsUnhandled(message, "") ? AxlDiagnostic.Severity.ERROR : severity, ErrorCode.JAVAC,
                    Messages.typeNames(message));
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
        Messages.Translated translated = Messages.translate(message, javaLines, javaLine, javaColumn, axlText(source, javaLine));
        int reportedLine = Messages.javaLine(translated.message(), javaLines, javaLine);
        if (reportedLine != javaLine) {
            javaLine = reportedLine;
            javaText = javaLines[javaLine - 1];
            javaColumn = Columns.javaColumn(javaLines, javaLine, diagnostic);
        }
        int line = Math.max(source.unit().lineMap().nearestAxlLine(javaLine), 1);
        String axlText = axlText(source, javaLine);
        int column = Columns.find(javaLines, source.unit().lineMap(), javaLine, javaColumn,
                axlText, Messages.placement(translated.message(), javaText));
        var result = new AxlDiagnostic(source.displayPath(), line, column, severity, translated.code(),
                otherFiles.reword(translated.message()));
        String check = ErrorProneMessages.check(message);
        if (check != null && severity == AxlDiagnostic.Severity.WARNING) {
            checks.put(result, check);
        }
        return result;
    }

    /** The .axl line a line of the generated file came from, or an empty string. */
    private static String axlText(GeneratedSource source, int javaLine) {
        int line = Math.max(source.unit().lineMap().nearestAxlLine(javaLine), 1);
        return line <= source.axlLines().size() ? source.axlLines().get(line - 1) : "";
    }

    /**
     * The warning with the name of its Error Prone check, so that `@SuppressWarnings` can name it, unless
     * named already holds the check: one warning per check says it.
     */
    public AxlDiagnostic namingCheck(AxlDiagnostic warning, Set<String> named) {
        String check = checks.get(warning);
        if (check == null || !named.add(check)) {
            return warning;
        }
        return new AxlDiagnostic(warning.file(), warning.line(), warning.column(), warning.severity(), warning.code(),
                warning.message() + ErrorProneMessages.suppression(check));
    }

    /** javac puts symbol and location details on extra lines, after a line that may end in `;` already. */
    static String oneLine(String message) {
        return message.lines().map(l -> l.strip().replaceAll("\\s+", " ").replaceFirst(";$", "")).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("; "));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

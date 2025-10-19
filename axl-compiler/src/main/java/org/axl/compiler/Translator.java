package org.axl.compiler;

import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.check.Analysis;
import org.axl.compiler.check.Checker;
import org.axl.compiler.gen.Generator;
import org.axl.compiler.parse.SourceParser;

/** Translates one .axl file to Java. Needs no classpath: everything is decided by syntax and names. */
public final class Translator {

    /** unit is null when there are errors. */
    public record Result(GeneratedUnit unit, List<AxlDiagnostic> diagnostics) {

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(AxlDiagnostic::isError);
        }
    }

    private Translator() {}

    /**
     * @param sourcePath  path relative to the source root with `/` separators; recorded in the line map
     * @param displayPath path used in diagnostics
     */
    public static Result translate(String sourcePath, String displayPath, String text) {
        return translate(sourcePath, displayPath, text, Options.DEFAULT);
    }

    public static Result translate(String sourcePath, String displayPath, String text, Options options) {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        SourceParser.Result parsed = SourceParser.parse(displayPath, className(sourcePath), text);
        diagnostics.addAll(parsed.diagnostics());
        if (parsed.file() == null) {
            return new Result(null, diagnostics);
        }
        SourceFile file = parsed.file();
        Analysis analysis = Checker.check(file, diagnostics);
        if (diagnostics.stream().anyMatch(AxlDiagnostic::isError)) {
            return new Result(null, diagnostics);
        }
        return new Result(Generator.generate(sourcePath, file, analysis, options), diagnostics);
    }

    public static String className(String sourcePath) {
        String name = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        return name.endsWith(".axl") ? name.substring(0, name.length() - 4) : name;
    }
}

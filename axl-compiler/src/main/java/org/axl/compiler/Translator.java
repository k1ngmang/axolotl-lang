package org.axl.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.check.TypeInference;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.check.Analysis;
import org.axl.compiler.check.Checker;
import org.axl.compiler.gen.Generator;
import org.axl.compiler.parse.SourceParser;

/** Translates one .axl file to Java. Needs no classpath: everything is decided by syntax and names. */
public final class Translator {

    /**
     * @param unit  null when there are errors
     * @param draft the unit types some top-level values as `Object` because their types are unknown yet
     */
    public record Result(GeneratedUnit unit, List<AxlDiagnostic> diagnostics, boolean draft) {

        Result(GeneratedUnit unit, List<AxlDiagnostic> diagnostics) {
            this(unit, diagnostics, false);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(AxlDiagnostic::isError);
        }
    }

    private static final Pattern LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'");

    private Translator() {}

    /**
     * @param sourcePath  path relative to the source root with `/` separators; recorded in the line map
     * @param displayPath path used in diagnostics
     */
    public static Result translate(String sourcePath, String displayPath, String text) {
        return translate(sourcePath, displayPath, text, Options.DEFAULT);
    }

    public static Result translate(String sourcePath, String displayPath, String text, Options options) {
        return LargeStack.call(() -> translateHere(sourcePath, displayPath, text, options),
                () -> new Result(null, List.of(AxlDiagnostic.error(displayPath, 1, 1, ErrorCode.SYNTAX,
                        "the code is nested too deeply to translate; split it into smaller expressions"))));
    }

    private static Result translateHere(String sourcePath, String displayPath, String text, Options options) {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        SourceParser.Result parsed = SourceParser.parse(displayPath, className(sourcePath), text);
        diagnostics.addAll(parsed.diagnostics());
        if (parsed.file() == null) {
            return new Result(null, diagnostics);
        }
        SourceFile file = parsed.file();
        String directory = sourcePath.contains("/") ? sourcePath.substring(0, sourcePath.lastIndexOf('/')).replace('/', '.') : null;
        if (directory != null && file.packageName() != null && !directory.equals(file.packageName())) {
            diagnostics.add(AxlDiagnostic.warning(displayPath, file.packagePos().line(), packageNameColumn(file, text),
                    ErrorCode.PACKAGE_MISMATCH, "the package `" + file.packageName() + "` does not match the directory `"
                            + directory.replace('.', '/') + "`; Java and the IDE look for the class in its package directory"));
        }
        Analysis analysis = Checker.check(file, diagnostics);
        // A syntax error that leaves a valid tree, such as a line starting with `+`, explains what the checker finds there.
        List<AxlDiagnostic> syntax = parsed.diagnostics().stream().filter(AxlDiagnostic::isError).toList();
        diagnostics.removeIf(d -> !syntax.contains(d)
                && syntax.stream().anyMatch(s -> s.line() == d.line() && s.column() == d.column()));
        diagnostics.sort(Comparator.comparingInt(AxlDiagnostic::line).thenComparingInt(AxlDiagnostic::column));
        if (diagnostics.stream().anyMatch(AxlDiagnostic::isError)) {
            return new Result(null, diagnostics);
        }
        Map<String, String> valueTypes = new HashMap<>();
        Map<String, AxlDiagnostic> reasons = new HashMap<>();
        Set<String> unknown = inferValueTypes(file, analysis, options, valueTypes, reasons, displayPath, text);
        if (!unknown.isEmpty() && !options.draft()) {
            for (Decl decl : file.decls()) {
                if (decl instanceof Decl.Value value && unknown.contains(value.name())) {
                    // An error in the initializer is the real problem; the missing type follows from it.
                    AxlDiagnostic reason = reasons.get(value.name());
                    diagnostics.add(reason != null ? reason
                            : AxlDiagnostic.error(displayPath, value.namePos().line(), value.namePos().column(), ErrorCode.TYPE_REQUIRED,
                                    "cannot infer the type of `" + value.name() + "`; write it: `val " + value.name() + ": Type = ...`"));
                }
            }
            return new Result(null, diagnostics);
        }
        unknown.forEach(name -> valueTypes.put(name, "Object"));
        return new Result(Generator.generate(sourcePath, file, analysis, options, valueTypes), diagnostics, !unknown.isEmpty());
    }

    /** The column of the name after `package`, where the package line is about. */
    private static int packageNameColumn(SourceFile file, String text) {
        List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        int line = file.packagePos().line();
        if (line > lines.size()) {
            return file.packagePos().column();
        }
        String packageLine = lines.get(line - 1);
        int name = packageLine.indexOf(file.packageName());
        return name < 0 ? file.packagePos().column() : packageLine.codePointCount(0, name) + 1;
    }

    /**
     * Fills in the Java types of top-level values without a written type: from the file itself first,
     * then from the compiler if the options give access to one. Returns the names still unknown.
     */
    private static Set<String> inferValueTypes(SourceFile file, Analysis analysis, Options options,
                                               Map<String, String> valueTypes, Map<String, AxlDiagnostic> reasons,
                                               String displayPath, String text) {
        Map<String, TypeRef> inferred = new HashMap<>();
        TypeInference.inferAll(file, inferred);
        inferred.forEach((name, type) -> valueTypes.put(name, Generator.javaType(type)));
        Set<String> unknown = new LinkedHashSet<>();
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value && value.type() == null && !valueTypes.containsKey(value.name())) {
                unknown.add(value.name());
            }
        }
        if (unknown.isEmpty() || options.javaTypes() == null) {
            return unknown;
        }
        String qualified = file.packageName().isEmpty() ? file.className() : file.packageName() + "." + file.className();
        GeneratedUnit probe = Generator.probe(file, analysis, options, valueTypes, unknown);
        Map<String, String> found = options.javaTypes().localTypes(qualified, probe.javaSource());
        for (String name : List.copyOf(unknown)) {
            String type = found.get(name);
            if (type != null && type.startsWith(JavaTypes.ERROR)) {
                reasons.put(name, reason(type.substring(JavaTypes.ERROR.length()), probe.lineMap(), valueNamed(file, name),
                        displayPath, text));
            } else if (type != null && options.draft() && type.replace(JavaTypes.NULLABLE, "").equals("Object")) {
                // The value may read a draft of another file, typed `Object` only until its own type is known.
                continue;
            } else if (type != null) {
                valueTypes.put(name, javaType(type, file));
                unknown.remove(name);
            }
        }
        return unknown;
    }

    /** The compiler's reason at the .axl line and name it points at, or at the value when it cannot be placed. */
    private static AxlDiagnostic reason(String entry, LineMap probeLines, Decl.Value value, String displayPath, String text) {
        String[] parts = entry.split(":", 3);
        int line = value.pos().line();
        int column = value.pos().column();
        if (parts.length == 3 && parts[0].matches("\\d+")) {
            int mapped = probeLines.nearestAxlLine(Integer.parseInt(parts[0]));
            List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
            if (mapped >= line && mapped <= lines.size()) {
                line = mapped;
                String axlLine = lines.get(line - 1);
                int from = line == value.pos().line() ? Math.max(0, axlLine.indexOf('=')) : 0;
                int index = parts[1].isEmpty() ? -1 : wordIndex(axlLine, parts[1], from);
                column = index >= 0 ? axlLine.codePointCount(0, index) + 1 : axlLine.length() - axlLine.stripLeading().length() + 1;
            }
            return AxlDiagnostic.error(displayPath, line, column, ErrorCode.JAVAC, parts[2]);
        }
        return AxlDiagnostic.error(displayPath, line, column, ErrorCode.JAVAC, entry);
    }

    /** Where the word stands on its own in the line, outside string and character literals, or -1. */
    private static int wordIndex(String line, String word, int from) {
        String code = LITERAL.matcher(line).replaceAll(m -> " ".repeat(m.group().length()));
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(word) + "(?![\\w$])").matcher(code);
        return matcher.find(from) ? matcher.start() : -1;
    }

    private static Decl.Value valueNamed(SourceFile file, String name) {
        return file.decls().stream().filter(d -> d instanceof Decl.Value v && v.name().equals(name))
                .map(Decl.Value.class::cast).findFirst().orElseThrow();
    }

    private static String javaType(String probed, SourceFile file) {
        if (!probed.startsWith(JavaTypes.NULLABLE)) {
            return withImports(probed, file);
        }
        String type = withImports(probed.substring(JavaTypes.NULLABLE.length()), file);
        if (type.endsWith("[]")) {
            // On an array the annotation goes before its brackets: `@Nullable String[]` is an array of nullable strings.
            return type.substring(0, type.length() - 2) + " " + JavaTypes.NULLABLE + "[]";
        }
        // A type-use annotation on a qualified name goes before the simple name: java.util.@Nullable List.
        int rawEnd = type.indexOf('<') < 0 ? type.length() : type.indexOf('<');
        int dot = type.lastIndexOf('.', rawEnd);
        return type.substring(0, dot + 1) + JavaTypes.NULLABLE + type.substring(dot + 1);
    }

    /** Types the file imports are written with their simple names, as a person would. */
    private static String withImports(String javaType, SourceFile file) {
        String result = javaType;
        for (Import imp : file.imports()) {
            if (imp.kind() == Import.Kind.TYPE) {
                result = result.replaceAll("\\b" + Pattern.quote(imp.name()) + "\\b", imp.simpleName());
            }
        }
        return result;
    }

    /** Whether a file has top-level values without a written type, whose type may depend on other code. */
    public static boolean infersValueTypes(String sourcePath, String text) {
        SourceFile file = SourceParser.parse(sourcePath, className(sourcePath), text).file();
        return file != null && file.decls().stream().anyMatch(d -> d instanceof Decl.Value value && value.type() == null);
    }

    public static String className(String sourcePath) {
        String name = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        return name.endsWith(".axl") ? name.substring(0, name.length() - 4) : name;
    }
}

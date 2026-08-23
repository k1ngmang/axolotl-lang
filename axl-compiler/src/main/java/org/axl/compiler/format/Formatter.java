package org.axl.compiler.format;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.LargeStack;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;

/**
 * Rewrites an .axl file in the one style of the documentation: 4 spaces of indentation, one space around
 * binary operators and after commas, sorted imports, at most one blank line in a row. Line breaks,
 * comments and the text of literals stay as they are. See docs/fmt.md.
 */
public final class Formatter {

    /**
     * @param text          the formatted file; the source itself when it has errors or could not be formatted
     * @param errors        the syntax errors of the source, which leave it unchanged
     * @param internalError why formatting would have changed the meaning of the file, which it then leaves unchanged;
     *                      null when formatting worked
     */
    public record Result(String text, List<AxlDiagnostic> errors, String internalError) {

        public boolean failed() {
            return !errors.isEmpty() || internalError != null;
        }
    }

    private static final Pattern POSITION = Pattern.compile("Pos\\[line=\\d+, column=\\d+]");
    private static final Pattern CLASS_FILE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.axl");

    private Formatter() {}

    /** @param path the file's path, for messages; its name also names the class, as for the translator */
    public static Result format(String path, String source) {
        boolean bom = source.startsWith("﻿");
        String text = (bom ? source.substring(1) : source).replace("\r\n", "\n").replace('\r', '\n');
        String lineEnd = source.contains("\r\n") ? "\r\n" : "\n";
        SourceParser.Result parsed = SourceParser.parse(path, Translator.className(path), text);
        List<AxlDiagnostic> errors = parsed.diagnostics().stream().filter(AxlDiagnostic::isError).toList();
        if (parsed.file() == null || !errors.isEmpty()) {
            return new Result(source, errors, null);
        }
        String formatted;
        try {
            formatted = LargeStack.call(() -> Layout.format(text), () -> null);
        } catch (RuntimeException e) {
            return new Result(source, List.of(), "the formatter failed: " + e);
        }
        if (formatted == null) {
            return new Result(source, List.of(), "the code is nested too deeply to format");
        }
        String result = (bom ? "﻿" : "") + (lineEnd.equals("\n") ? formatted : formatted.replace("\n", lineEnd));
        if (result.equals(source)) {
            return new Result(source, List.of(), null);
        }
        String mismatch = mismatch(path, text, formatted);
        return mismatch == null ? new Result(result, List.of(), null) : new Result(source, List.of(), mismatch);
    }

    /** Why the formatted text means something else than the source, or null when both give the same tree and Java. */
    static String mismatch(String path, String before, String after) {
        // A name that is no class name fails the translation, which would then compare only the errors.
        String sourcePath = CLASS_FILE_NAME.matcher(path.substring(path.lastIndexOf('/') + 1)).matches() ? path : "Main.axl";
        SourceParser.Result formatted = SourceParser.parse(path, Translator.className(sourcePath), after);
        if (formatted.file() == null || formatted.diagnostics().stream().anyMatch(AxlDiagnostic::isError)) {
            return "the formatted text does not parse: " + formatted.diagnostics().stream()
                    .filter(AxlDiagnostic::isError).map(AxlDiagnostic::format).findFirst().orElse("");
        }
        SourceParser.Result original = SourceParser.parse(path, Translator.className(sourcePath), before);
        if (!shape(original.file()).equals(shape(formatted.file()))) {
            return "the formatted text parses to another syntax tree";
        }
        if (!java(Translator.translate(sourcePath, path, before)).equals(java(Translator.translate(sourcePath, path, after)))) {
            return "the formatted text translates to other Java";
        }
        return null;
    }

    /** The tree without positions, with the imports in the order the formatter sorts them. */
    private static String shape(SourceFile file) {
        List<Import> imports = file.imports().stream().sorted(Comparator.comparing(Import::name)).toList();
        SourceFile unplaced = new SourceFile(file.path(), file.className(), null, file.packageName(), imports, file.decls());
        return POSITION.matcher(unplaced.toString()).replaceAll("Pos");
    }

    /** The Java with its imports sorted, or the errors without their positions. */
    private static String java(Translator.Result result) {
        if (result.unit() == null) {
            return String.join("\n", result.diagnostics().stream()
                    .map(d -> d.severity() + " " + d.code().id() + " " + d.message()).sorted().toList());
        }
        List<String> lines = new java.util.ArrayList<>(result.unit().javaSource().lines().toList());
        List<String> imports = lines.stream().filter(l -> l.startsWith("import ")).sorted().toList();
        int next = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("import ")) {
                lines.set(i, imports.get(next++));
            }
        }
        return String.join("\n", lines);
    }
}

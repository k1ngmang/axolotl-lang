package org.axl.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.axl.compiler.LineMap;

/**
 * Finds the .axl column of a javac diagnostic. Line maps only know lines, so this looks for the
 * name javac points at in the generated code and finds the same occurrence of it in the .axl line.
 * Columns count code points, as editors show them.
 */
final class Columns {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'");
    private static final Pattern COMMENT = Pattern.compile("//.*|/\\*.*?(\\*/|$)");
    private static final Pattern JAVA_SIGNATURE = Pattern.compile("^\\s*(?:public|private) static (.+?) ([\\w$]+)\\((.*)\\) \\{$");
    private static final Pattern AXL_SIGNATURE = Pattern.compile("^\\s*(?:private\\s+)?fn\\s+[\\w$]+\\s*\\(");

    private Columns() {}

    /** A part of a line: [start, end). */
    private record Region(int start, int end) {
        boolean contains(int index) {
            return index >= start && index < end;
        }
    }

    /**
     * @param javaLines  the generated file
     * @param lineMap    its line map
     * @param javaLine   1-based line of the diagnostic
     * @param javaColumn 1-based column in the generated line, or a non-positive value if unknown
     * @param axlLine    the text of the .axl line the diagnostic belongs to
     * @return 1-based column in the .axl line
     */
    static int find(String[] javaLines, LineMap lineMap, int javaLine, long javaColumn, String axlLine) {
        String javaText = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        int fallback = firstNonBlank(axlLine);
        if (javaColumn <= 0 || javaColumn > javaText.length()) {
            return fallback;
        }
        int column = (int) javaColumn - 1;
        // javac places a call at its parenthesis: the name before it, if the .axl line has it, else the next one.
        if (javaText.charAt(column) == '(' && column > 0 && Character.isJavaIdentifierPart(javaText.charAt(column - 1))) {
            Integer before = column(javaLines, lineMap, javaLine, column - 1, axlLine);
            if (before != null) {
                return axlLine.codePointCount(0, before) + 1;
            }
        }
        Integer found = column(javaLines, lineMap, javaLine, column, axlLine);
        return found == null ? fallback : axlLine.codePointCount(0, found) + 1;
    }

    /** The index in the .axl line of the name at or after the index in the Java line, or null. */
    private static Integer column(String[] javaLines, LineMap lineMap, int javaLine, int index, String axlLine) {
        String javaText = javaLines[javaLine - 1];
        int start = index;
        while (start < javaText.length() && !Character.isJavaIdentifierPart(javaText.charAt(start))) {
            start++;
        }
        int at = start;
        Region javaName = names(javaText).stream().filter(n -> n.contains(at)).findFirst().orElse(null);
        if (javaName == null) {
            return null;
        }
        String word = javaText.substring(javaName.start(), javaName.end());
        if (word.startsWith("$")) {
            return null;
        }
        Integer found = inSignature(javaText, javaName, word, axlLine);
        return found != null ? found : inBody(javaLines, lineMap, javaLine, javaName, word, axlLine);
    }

    /** `fn f(a: A) -> R` becomes `static R f(A a) {`: the result type moves to the front. */
    private static Integer inSignature(String javaText, Region javaName, String word, String axlLine) {
        Matcher java = JAVA_SIGNATURE.matcher(javaText);
        Matcher axl = AXL_SIGNATURE.matcher(axlLine);
        if (!java.matches() || !axl.lookingAt()) {
            return null;
        }
        int paramsEnd = closingParen(axlLine, axl.end() - 1);
        if (paramsEnd < 0) {
            return null;
        }
        Region axlParams = new Region(axl.end(), paramsEnd);
        int arrow = axlLine.indexOf("->", paramsEnd);
        int resultEnd = axlLine.indexOf('=', arrow + 2) >= 0 ? axlLine.indexOf('=', arrow + 2) : axlLine.length();
        Region axlResult = arrow < 0 ? null : new Region(arrow + 2, resultEnd);
        Region javaParams = new Region(java.start(3), java.end(3));
        Region javaResult = new Region(java.start(1), java.end(1));
        if (javaName.start() == java.start(2)) {
            return nth(axlLine, new Region(0, axl.end()), word, 0);
        }
        if (javaParams.contains(javaName.start())) {
            return nth(axlLine, axlParams, word, index(javaText, javaParams, javaName, word));
        }
        if (javaResult.contains(javaName.start()) && axlResult != null) {
            return nth(axlLine, axlResult, word, index(javaText, javaResult, javaName, word));
        }
        return null;
    }

    /**
     * The same occurrence counted from the start over all Java lines of this .axl line, which keep
     * the order of the code; a signature on the line is left out on both sides.
     */
    private static Integer inBody(String[] javaLines, LineMap lineMap, int javaLine, Region javaName, String word,
                                  String axlLine) {
        int axl = lineMap.nearestAxlLine(javaLine);
        int before = 0;
        for (int line = 1; line < javaLine; line++) {
            if (lineMap.axlLine(line) == axl && !JAVA_SIGNATURE.matcher(javaLines[line - 1]).matches()) {
                before += index(javaLines[line - 1], null, null, word);
            }
        }
        String javaText = javaLines[javaLine - 1];
        int occurrence = before + index(javaText, null, javaName, word);
        int start = 0;
        Matcher signature = AXL_SIGNATURE.matcher(axlLine);
        if (signature.lookingAt()) {
            int paramsEnd = closingParen(axlLine, signature.end() - 1);
            int body = paramsEnd < 0 ? -1 : axlLine.indexOf('=', paramsEnd);
            start = body < 0 ? axlLine.length() : body + 1;
        }
        return nth(axlLine, new Region(start, axlLine.length()), word, occurrence);
    }

    /** How many times the word occurs in the region before the name, or in all of it when name is null. */
    private static int index(String line, Region region, Region name, String word) {
        int count = 0;
        for (Region n : names(line)) {
            boolean inside = region == null || region.contains(n.start());
            if (name != null && n.start() >= name.start()) {
                break;
            }
            if (inside && line.substring(n.start(), n.end()).equals(word)) {
                count++;
            }
        }
        return count;
    }

    private static Integer nth(String line, Region region, String word, int occurrence) {
        int count = 0;
        for (Region n : names(line)) {
            if (region.contains(n.start()) && line.substring(n.start(), n.end()).equals(word) && count++ == occurrence) {
                return n.start();
            }
        }
        return null;
    }

    private static int closingParen(String line, int open) {
        int depth = 0;
        for (int i = open; i < line.length(); i++) {
            if (line.charAt(i) == '(') {
                depth++;
            } else if (line.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Every name outside string and char literals and comments. */
    private static List<Region> names(String line) {
        String code = LITERAL.matcher(line).replaceAll(m -> " ".repeat(m.group().length()));
        code = COMMENT.matcher(code).replaceAll(m -> " ".repeat(m.group().length()));
        List<Region> result = new ArrayList<>();
        Matcher matcher = NAME.matcher(code);
        while (matcher.find()) {
            result.add(new Region(matcher.start(), matcher.end()));
        }
        return result;
    }

    static int firstNonBlank(String text) {
        int column = 0;
        while (column < text.length() && Character.isWhitespace(text.charAt(column))) {
            column++;
        }
        return text.codePointCount(0, column) + 1;
    }
}

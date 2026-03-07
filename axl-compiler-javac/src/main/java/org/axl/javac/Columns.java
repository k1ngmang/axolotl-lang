package org.axl.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import org.axl.compiler.LineMap;

/**
 * Finds the .axl column of a javac diagnostic. Line maps only know lines, so this looks for the
 * name javac points at in the generated code and finds the same occurrence of it in the .axl line.
 * Columns count code points, as editors show them.
 */
final class Columns {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d[\\w.]*");
    private static final Pattern IMPLICIT_IT = Pattern.compile("it\\s*->");
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
        Matcher literal = LITERAL.matcher(javaText).region(index, javaText.length());
        if (literal.lookingAt() && axlLine.contains(literal.group())) {
            // A string or character javac points at is written the same way in the .axl line.
            return axlLine.indexOf(literal.group());
        }
        Matcher number = NUMBER.matcher(javaText).region(index, javaText.length());
        if (number.lookingAt()) {
            Matcher same = Pattern.compile("(?<![\\w.])" + Pattern.quote(number.group()) + "(?![\\w.])").matcher(axlLine);
            if (same.find(bodyStart(axlLine))) {
                return same.start();
            }
        }
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
        if (word.equals("new")) {
            // `new StringBuilder(...)` is `StringBuilder(...)` in Axolotl.
            return column(javaLines, lineMap, javaLine, javaName.end(), axlLine);
        }
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
        return nth(axlLine, new Region(bodyStart(axlLine), axlLine.length()), word, occurrence);
    }

    /** Where the code after a signature on the line starts, or 0. */
    private static int bodyStart(String axlLine) {
        Matcher signature = AXL_SIGNATURE.matcher(axlLine);
        if (!signature.lookingAt()) {
            return 0;
        }
        int paramsEnd = closingParen(axlLine, signature.end() - 1);
        int body = paramsEnd < 0 ? -1 : axlLine.indexOf('=', paramsEnd);
        return body < 0 ? axlLine.length() : body + 1;
    }

    /** How many times the word occurs in the region before the name, or in all of it when name is null. */
    private static int index(String line, Region region, Region name, String word) {
        int count = 0;
        for (Region n : names(line)) {
            // `{ it.length() }` becomes `it -> it.length()`: the parameter is not in the .axl line.
            if (IMPLICIT_IT.matcher(line).region(n.start(), line.length()).lookingAt()) {
                continue;
            }
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
        int commentEnd = code.indexOf("*/");
        if (commentEnd >= 0 && !code.substring(0, commentEnd).contains("/*")) {
            // The end of a comment that started on an earlier line.
            code = " ".repeat(commentEnd + 2) + code.substring(commentEnd + 2);
        }
        code = COMMENT.matcher(code).replaceAll(m -> " ".repeat(m.group().length()));
        List<Region> result = new ArrayList<>();
        Matcher matcher = NAME.matcher(code);
        while (matcher.find()) {
            result.add(new Region(matcher.start(), matcher.end()));
        }
        return result;
    }

    /** The 1-based column in the Java line, counting a tab as one character as the line text does; javac widens tabs. */
    static long javaColumn(String[] javaLines, int javaLine, Diagnostic<?> diagnostic) {
        long position = diagnostic.getPosition();
        if (position == Diagnostic.NOPOS || javaLine < 1 || javaLine > javaLines.length) {
            return diagnostic.getColumnNumber();
        }
        long lineStart = 0;
        for (int i = 0; i < javaLine - 1; i++) {
            lineStart += javaLines[i].length() + 1;
        }
        return position - lineStart + 1;
    }

    static int firstNonBlank(String text) {
        int column = 0;
        while (column < text.length() && Character.isWhitespace(text.charAt(column))) {
            column++;
        }
        return text.codePointCount(0, column) + 1;
    }
}

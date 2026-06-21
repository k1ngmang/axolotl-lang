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

    private static final Pattern OPERATOR = Pattern.compile("==|!=|<=|>=|&&|\\|\\||[-+*/%<>!]");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LITERAL = Pattern.compile("\"[^\"\\\\]*+(\\\\.[^\"\\\\]*+)*+\"|'[^'\\\\]*+(\\\\.[^'\\\\]*+)*+'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d[\\w.]*");
    private static final Pattern IMPLICIT_IT = Pattern.compile("it\\s*->");
    private static final Pattern COMMENT = Pattern.compile("//.*|/\\*.*?(\\*/|$)");
    private static final Pattern JAVA_SIGNATURE = Pattern.compile("^\\s*(?:public|private) (?:static )?(.+?) ([\\w$]+)\\((.*)\\) \\{$");
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
     * @param placement  what the message is about, from {@link Messages#placement}
     * @return 1-based column in the .axl line
     */
    static int find(String[] javaLines, LineMap lineMap, int javaLine, long javaColumn, String axlLine,
                    Messages.Placement placement) {
        String javaText = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        Integer anchored = placement.anchor() == null ? null : anchored(placement.anchor(), axlLine);
        if (anchored != null && placement.anchorFirst()) {
            return axlLine.codePointCount(0, anchored) + 1;
        }
        Integer found = javaColumn <= 0 || javaColumn > javaText.length() ? null
                : index(javaLines, lineMap, javaLine, (int) javaColumn - 1, axlLine, placement.operator());
        if (found == null) {
            found = anchored;
        }
        return found == null ? fallback(axlLine) : axlLine.codePointCount(0, found) + 1;
    }

    private static Integer index(String[] javaLines, LineMap lineMap, int javaLine, int column, String axlLine,
                                 boolean operator) {
        String javaText = javaLines[javaLine - 1];
        Matcher symbol = OPERATOR.matcher(javaText).region(column, javaText.length());
        Integer same = operator && symbol.lookingAt()
                ? sameOccurrence(javaLines, lineMap, javaLine, column, operator(symbol.group()), true, axlLine) : null;
        if (same != null) {
            return same;
        }
        // javac places a call at its parenthesis: the name before it, if the .axl line has it, else the next one.
        if (javaText.charAt(column) == '(' && column > 0 && Character.isJavaIdentifierPart(javaText.charAt(column - 1))) {
            Integer before = column(javaLines, lineMap, javaLine, column - 1, axlLine);
            if (before != null) {
                return before;
            }
        }
        return column(javaLines, lineMap, javaLine, column, axlLine);
    }

    /** The index of the anchor in the .axl line, of its group 1 if it has one, or null. */
    private static Integer anchored(Pattern anchor, String axlLine) {
        Matcher matcher = anchor.matcher(axlLine);
        // The result type is the one anchor in the signature, which the others skip.
        if (!matcher.find(anchor == Messages.RESULT_TYPE ? 0 : bodyStart(axlLine))) {
            return null;
        }
        return matcher.groupCount() > 0 && matcher.group(1) != null ? matcher.start(1) : matcher.start();
    }

    /** The index in the .axl line of the name at or after the index in the Java line, or null. */
    private static Integer column(String[] javaLines, LineMap lineMap, int javaLine, int index, String axlLine) {
        String javaText = javaLines[javaLine - 1];
        Matcher literal = LITERAL.matcher(javaText).region(index, javaText.length());
        if (literal.lookingAt() && axlLine.contains(literal.group())) {
            // A string or character javac points at is written the same way in the .axl line.
            Integer same = sameOccurrence(javaLines, lineMap, javaLine, index, Pattern.compile(Pattern.quote(literal.group())), false, axlLine);
            return same != null ? same : axlLine.indexOf(literal.group());
        }
        Matcher number = NUMBER.matcher(javaText).region(index, javaText.length());
        if (number.lookingAt()) {
            Pattern samePattern = Pattern.compile("(?<![\\w.])" + Pattern.quote(number.group()) + "(?![\\w.])");
            Integer occurrence = sameOccurrence(javaLines, lineMap, javaLine, index, samePattern, true, axlLine);
            Matcher same = samePattern.matcher(axlLine);
            if (occurrence != null) {
                return occurrence;
            }
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

    /** An operator alone, not the start or end of a longer one such as `<=` or `->`. */
    private static Pattern operator(String text) {
        return Pattern.compile("(?<![=!<>&|+\\-*/%])" + Pattern.quote(text) + "(?![=&|+\\-*/%>])");
    }

    /**
     * The same occurrence of a literal or operator in the .axl line, counted over the Java lines of that
     * line as {@link #inBody} counts names; literals and comments do not count for an operator.
     */
    private static Integer sameOccurrence(String[] javaLines, LineMap lineMap, int javaLine, int index, Pattern token,
                                          boolean inCode, String axlLine) {
        int axl = lineMap.nearestAxlLine(javaLine);
        int occurrence = 0;
        for (int line = 1; line < javaLine; line++) {
            if (lineMap.axlLine(line) == axl && !JAVA_SIGNATURE.matcher(javaLines[line - 1]).matches()) {
                occurrence += starts(token, inCode, javaLines[line - 1], Integer.MAX_VALUE).size();
            }
        }
        occurrence += starts(token, inCode, javaLines[javaLine - 1], index).size();
        List<Integer> inAxl = starts(token, inCode, axlLine, Integer.MAX_VALUE).stream().filter(s -> s >= bodyStart(axlLine)).toList();
        return occurrence < inAxl.size() ? inAxl.get(occurrence) : null;
    }

    /** Where the token starts in the line before the limit; inCode leaves out literals and comments. */
    private static List<Integer> starts(Pattern token, boolean inCode, String line, int limit) {
        String code = !inCode ? line : COMMENT.matcher(LITERAL.matcher(line).replaceAll(m -> " ".repeat(m.group().length())))
                .replaceAll(m -> " ".repeat(m.group().length()));
        List<Integer> result = new ArrayList<>();
        Matcher matcher = token.matcher(code);
        while (matcher.find() && matcher.start() < limit) {
            result.add(matcher.start());
        }
        return result;
    }

    /** The start of the line, or of an expression body after a signature. */
    private static int fallback(String axlLine) {
        int body = bodyStart(axlLine);
        if (body == 0 || body == axlLine.length()) {
            return firstNonBlank(axlLine);
        }
        int offset = axlLine.codePointCount(0, body);
        return offset + firstNonBlank(axlLine.substring(body));
    }

    /** Where the code after a signature on the line starts, or 0. */
    private static int bodyStart(String axlLine) {
        Matcher signature = AXL_SIGNATURE.matcher(axlLine);
        if (!signature.lookingAt()) {
            return 0;
        }
        int paramsEnd = closingParen(axlLine, signature.end() - 1);
        int equals = paramsEnd < 0 ? -1 : axlLine.indexOf('=', paramsEnd);
        int brace = paramsEnd < 0 ? -1 : axlLine.indexOf('{', paramsEnd);
        // `fn f() = x` or `fn f() { return x }`: whichever starts the body first.
        int body = equals < 0 ? brace : brace < 0 ? equals : Math.min(equals, brace);
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

package org.axl.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the .axl column of a javac diagnostic. Line maps only know lines, so this looks for the
 * name javac points at in the generated line and finds the same occurrence of it in the .axl line.
 */
final class Columns {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'");

    private Columns() {}

    /**
     * @param javaColumn 1-based column in the generated line, or a non-positive value if unknown
     * @return 1-based column in the .axl line
     */
    static int find(String javaLine, long javaColumn, String axlLine) {
        int fallback = firstNonBlank(axlLine);
        if (javaColumn <= 0 || javaColumn > javaLine.length()) {
            return fallback;
        }
        int at = (int) javaColumn - 1;
        while (at < javaLine.length() && !Character.isJavaIdentifierPart(javaLine.charAt(at))) {
            at++;
        }
        List<int[]> javaNames = names(javaLine);
        for (int[] name : javaNames) {
            if (at >= name[0] && at < name[1]) {
                String word = javaLine.substring(name[0], name[1]);
                if (word.startsWith("$")) {
                    return fallback;
                }
                // Counted from the end: a function's signature shares its .axl line with an expression
                // body, but not its Java line.
                int fromEnd = (int) javaNames.stream()
                        .filter(n -> n[0] > name[0] && javaLine.substring(n[0], n[1]).equals(word)).count();
                List<int[]> axlMatches = names(axlLine).stream()
                        .filter(n -> axlLine.substring(n[0], n[1]).equals(word)).toList();
                return fromEnd < axlMatches.size() ? axlMatches.get(axlMatches.size() - 1 - fromEnd)[0] + 1 : fallback;
            }
        }
        return fallback;
    }

    /** Start and end of every name outside string and char literals. */
    private static List<int[]> names(String line) {
        String code = LITERAL.matcher(line).replaceAll(m -> " ".repeat(m.group().length()));
        List<int[]> result = new ArrayList<>();
        Matcher matcher = NAME.matcher(code);
        while (matcher.find()) {
            result.add(new int[] {matcher.start(), matcher.end()});
        }
        return result;
    }

    static int firstNonBlank(String text) {
        int column = 0;
        while (column < text.length() && Character.isWhitespace(text.charAt(column))) {
            column++;
        }
        return column + 1;
    }
}

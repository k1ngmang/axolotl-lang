package org.axl.compiler.gen;

import java.util.ArrayList;
import java.util.List;

/** Writes Java line by line and remembers which .axl line produced each one. */
final class Emitter {

    private static final String INDENT = "    ";
    // Separates the .axl line number from the code in multi-line expression text.
    private static final char MARK = '\u0000';

    private final StringBuilder out = new StringBuilder();
    private final List<Integer> lineMap = new ArrayList<>();
    private int indent;

    void indent() {
        indent++;
    }

    void dedent() {
        indent--;
    }

    void blank() {
        out.append('\n');
        lineMap.add(0);
    }

    /** Emits code that may span several lines when it contains a block lambda. */
    void line(int axlLine, String code) {
        String[] parts = code.split("\n", -1);
        write(axlLine, parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int mark = part.indexOf(MARK, 1);
            write(Integer.parseInt(part.substring(1, mark)), part.substring(mark + 1));
        }
    }

    private void write(int axlLine, String code) {
        out.append(INDENT.repeat(indent)).append(code).append('\n');
        lineMap.add(axlLine);
    }

    /**
     * Renders what was emitted as text for embedding in an expression of an enclosing emitter;
     * every line keeps its .axl line and its indentation relative to that emitter.
     */
    String embedded() {
        if (out.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = out.toString().split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append('\n').append(MARK).append(lineMap.get(i)).append(MARK).append(lines[i]);
        }
        return sb.toString();
    }

    static String continuation(int axlLine, String code) {
        return "\n" + MARK + axlLine + MARK + code;
    }

    /** A continuation line of an expression, indented twice. */
    static String continued(int axlLine, String code) {
        return continuation(axlLine, INDENT + INDENT + code);
    }

    String text() {
        return out.toString();
    }

    int[] lineMap() {
        return lineMap.stream().mapToInt(Integer::intValue).toArray();
    }
}

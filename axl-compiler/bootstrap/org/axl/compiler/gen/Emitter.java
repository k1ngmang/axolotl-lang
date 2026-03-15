package org.axl.compiler.gen;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NullMarked public final class Emitter {
    private final StringBuilder out = new StringBuilder();
    private final ArrayList<Integer> lines = new ArrayList<Integer>();
    private int depth = 0;
    public void indent() {
        depth += 1;
    }
    public void dedent() {
        depth -= 1;
    }
    public void blank() {
        out.append('\n');
        lines.add(0);
    }
    public void line(int axlLine, String code) {
        final var parts = Arrays.asList(code.split("\n", -1));
        write(axlLine, parts.get(0));
        for (final var part : parts.subList(1, parts.size())) {
            final var mark = part.indexOf(markChar, 1);
            write(Integer.parseInt(part.substring(1, mark)), part.substring(mark + 1));
        }
    }
    private void write(int axlLine, String code) {
        out.append(indentation.repeat(depth)).append(code).append('\n');
        lines.add(axlLine);
    }
    public String embedded() {
        if (out.isEmpty()) {
            return "";
        }
        final var sb = new StringBuilder();
        var index = 0;
        for (final var line : Arrays.asList(out.toString().split("\n"))) {
            sb.append('\n').append(markChar).append(lines.get(index)).append(markChar).append(line);
            index += 1;
        }
        return sb.toString();
    }
    public String text() {
        return out.toString();
    }
    public List<Integer> lineNumbers() {
        return lines;
    }
    private static final String indentation = "    ";
    private static final Character markChar = '\000';
    public static String continuation(int axlLine, String code) {
        return "\n" + markChar + axlLine + markChar + code;
    }
    public static String continued(int axlLine, String code) {
        return continuation(axlLine, indentation + indentation + code);
    }
}

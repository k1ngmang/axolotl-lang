package org.axl.compiler;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps lines of a generated .java file to lines of its .axl source.
 * Stored next to the .java file as `*.axlmap`; the format is described in docs/axlmap.md.
 */
public final class LineMap {

    public static final int VERSION = 1;

    private static final Pattern SOURCE = Pattern.compile("\"source\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern LINES = Pattern.compile("\"lines\"\\s*:\\s*\\[([^\\]]*)]");

    private final String source;
    private final int[] lines;

    /** lines[i] is the .axl line of .java line i + 1, or 0 when it has none. */
    public LineMap(String source, int[] lines) {
        this.source = source;
        this.lines = lines.clone();
    }

    public String source() {
        return source;
    }

    public int javaLineCount() {
        return lines.length;
    }

    /** The .axl line for a 1-based .java line, or 0 when unknown. */
    public int axlLine(int javaLine) {
        return javaLine >= 1 && javaLine <= lines.length ? lines[javaLine - 1] : 0;
    }

    /** Like {@link #axlLine} but falls back to the closest mapped line above. */
    public int nearestAxlLine(int javaLine) {
        for (int line = Math.min(javaLine, lines.length); line >= 1; line--) {
            if (lines[line - 1] != 0) {
                return lines[line - 1];
            }
        }
        return 0;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": ").append(VERSION).append(",\n  \"source\": \"")
                .append(source.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n  \"lines\": [");
        for (int i = 0; i < lines.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(lines[i]);
        }
        return sb.append("]\n}\n").toString();
    }

    public static LineMap fromJson(String json) {
        Matcher source = SOURCE.matcher(json);
        Matcher lines = LINES.matcher(json);
        if (!source.find() || !lines.find()) {
            throw new IllegalArgumentException("not an axlmap file");
        }
        String body = lines.group(1).trim();
        int[] values = body.isEmpty() ? new int[0]
                : Arrays.stream(body.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        String path = source.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        return new LineMap(path, values);
    }
}

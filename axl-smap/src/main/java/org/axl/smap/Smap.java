package org.axl.smap;

import java.util.HashMap;
import java.util.Map;
import org.axl.compiler.LineMap;

/**
 * A JSR-45 source map for one generated class with the stratum `Axolotl`. The default stratum stays
 * `Java`, which is whatever the class file itself says: the generated .java lines of a class as javac
 * wrote it, or the .axl lines of a class whose line numbers were {@linkplain SmapInjector rewritten}.
 *
 * @param javaLineToAxl line in the class file to .axl line
 */
public record Smap(String javaFileName, String axlFileName, String axlPath, Map<Integer, Integer> javaLineToAxl) {

    public static final String STRATUM = "Axolotl";

    public static Smap of(LineMap lineMap, String javaFileName) {
        Map<Integer, Integer> lines = new HashMap<>();
        for (int javaLine = 1; javaLine <= lineMap.javaLineCount(); javaLine++) {
            int axlLine = lineMap.axlLine(javaLine);
            if (axlLine > 0) {
                lines.put(javaLine, axlLine);
            }
        }
        String path = lineMap.source();
        return new Smap(javaFileName, path.substring(path.lastIndexOf('/') + 1), path, lines);
    }

    /**
     * The SMAP of a class whose SourceFile and LineNumberTable already name the .axl file: the output
     * file is the .axl file and the stratum maps every mapped .axl line to itself.
     */
    public static Smap rewritten(LineMap lineMap) {
        Map<Integer, Integer> lines = new HashMap<>();
        for (int javaLine = 1; javaLine <= lineMap.javaLineCount(); javaLine++) {
            int axlLine = lineMap.axlLine(javaLine);
            if (axlLine > 0) {
                lines.put(axlLine, axlLine);
            }
        }
        String path = lineMap.source();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new Smap(name, name, path, lines);
    }

    /** True for the SMAP of a class whose line numbers are .axl lines, see {@link #rewritten}. */
    public boolean isRewritten() {
        return javaFileName.equals(axlFileName);
    }

    /** The .axl line of a .java line, or 0. */
    public int axlLine(int javaLine) {
        return javaLineToAxl.getOrDefault(javaLine, 0);
    }

    /**
     * Consecutive .java lines from the same .axl line become one `*L` entry `axl#1:java,count`;
     * consecutive .java lines from consecutive .axl lines become `axl#1,count:java`.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("SMAP\n").append(javaFileName).append("\nJava\n*S ").append(STRATUM).append('\n');
        sb.append("*F\n+ 1 ").append(axlFileName).append('\n').append(axlPath).append('\n');
        sb.append("*L\n");
        int maxLine = javaLineToAxl.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int javaLine = 1;
        while (javaLine <= maxLine) {
            int axl = axlLine(javaLine);
            if (axl == 0) {
                javaLine++;
                continue;
            }
            int count = 1;
            while (axlLine(javaLine + count) == axl) {
                count++;
            }
            int run = 1;
            while (count == 1 && axlLine(javaLine + run) == axl + run) {
                run++;
            }
            sb.append(axl).append("#1");
            if (run > 1) {
                sb.append(',').append(run);
            }
            sb.append(':').append(javaLine);
            if (count > 1) {
                sb.append(',').append(count);
            }
            sb.append('\n');
            javaLine += Math.max(count, run);
        }
        return sb.append("*E\n").toString();
    }

    /** Reads the `Axolotl` stratum of an SMAP; returns null when there is none. */
    public static Smap parse(String text) {
        String[] lines = text.split("\r?\n");
        if (lines.length < 3 || !lines[0].equals("SMAP")) {
            return null;
        }
        String javaFile = lines[1];
        String axlFile = null;
        String axlPath = null;
        Map<Integer, Integer> map = new HashMap<>();
        String section = "";
        boolean inStratum = false;
        for (int i = 3; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("*S ")) {
                inStratum = line.substring(3).strip().equals(STRATUM);
                continue;
            }
            if (line.startsWith("*")) {
                section = line;
                continue;
            }
            if (!inStratum) {
                continue;
            }
            if (section.equals("*F") && line.startsWith("+ ")) {
                axlFile = line.substring(line.indexOf(' ', 2) + 1);
                axlPath = i + 1 < lines.length ? lines[++i] : axlFile;
            } else if (section.equals("*F")) {
                axlFile = line.substring(line.indexOf(' ') + 1);
                axlPath = axlFile;
            } else if (section.equals("*L")) {
                readLineEntry(line, map);
            }
        }
        return axlFile == null ? null : new Smap(javaFile, axlFile, axlPath, map);
    }

    /** `InputStart[#FileId][,Repeat]:OutputStart[,Increment]`. */
    private static void readLineEntry(String entry, Map<Integer, Integer> map) {
        String[] sides = entry.split(":");
        String input = sides[0];
        int repeat = 1;
        int comma = input.indexOf(',');
        if (comma >= 0) {
            repeat = Integer.parseInt(input.substring(comma + 1));
            input = input.substring(0, comma);
        }
        int hash = input.indexOf('#');
        int inputStart = Integer.parseInt(hash >= 0 ? input.substring(0, hash) : input);
        String[] output = sides[1].split(",");
        int outputStart = Integer.parseInt(output[0]);
        int increment = output.length > 1 ? Integer.parseInt(output[1]) : 1;
        for (int k = 0; k < repeat; k++) {
            for (int j = 0; j < increment; j++) {
                map.put(outputStart + k * increment + j, inputStart + k);
            }
        }
    }
}

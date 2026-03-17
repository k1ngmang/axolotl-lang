package org.axl.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.axl.compiler.LineMap;
import org.axl.smap.SmapInjector;

/**
 * Removes what `axl build` generated for .axl files that were deleted since the previous build. A
 * state file in the generated directory names the source directory and its .axl files; outputs are
 * removed only for files listed there whose .axlmap names them as its source, so neither files
 * written by anyone else nor the outputs of another source directory built into the same place go.
 */
final class StaleOutputs {

    static final String STATE = ".axl-build";

    private StaleOutputs() {}

    static void remove(Path sourceDir, Path generatedDir, Path classesDir) throws IOException {
        Path state = generatedDir.resolve(STATE);
        if (!Files.isRegularFile(state)) {
            return;
        }
        List<String> lines = Files.readAllLines(state, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(absolute(sourceDir).toString())) {
            return;
        }
        for (String base : lines.subList(1, lines.size())) {
            Path mapFile = generatedDir.resolve(base + ".axlmap");
            if (!Files.exists(sourceDir.resolve(base + ".axl")) && isMapOf(mapFile, base)) {
                removeOutputs(sourceDir, generatedDir, classesDir, base);
                Files.delete(mapFile);
            }
        }
    }

    /** Remembers the .axl files of sourceDir for the next {@link #remove}. */
    static void record(Path sourceDir, Path generatedDir) throws IOException {
        if (!Files.isDirectory(generatedDir)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(absolute(sourceDir).toString());
        try (Stream<Path> files = Files.walk(sourceDir)) {
            files.filter(p -> p.toString().endsWith(".axl") && Files.isRegularFile(p))
                    .map(p -> sourceDir.relativize(p).toString().replace('\\', '/'))
                    .map(relative -> relative.substring(0, relative.length() - ".axl".length()))
                    .sorted()
                    .forEach(lines::add);
        }
        Files.write(generatedDir.resolve(STATE), lines, StandardCharsets.UTF_8);
    }

    private static boolean isMapOf(Path mapFile, String base) throws IOException {
        if (!Files.isRegularFile(mapFile)) {
            return false;
        }
        try {
            return LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8)).source().equals(base + ".axl");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void removeOutputs(Path sourceDir, Path generatedDir, Path classesDir, String base)
            throws IOException {
        Path java = generatedDir.resolve(base + ".java");
        // With -s pointing into the sources, a Java file that replaced the deleted .axl file is the user's.
        if (!absolute(java).equals(absolute(sourceDir.resolve(base + ".java")))) {
            Files.deleteIfExists(java);
        }
        Path topLevel = classesDir.resolve(base + ".class");
        if (Files.isDirectory(topLevel.getParent())) {
            for (Path classFile : SmapInjector.classFilesOf(topLevel)) {
                Files.deleteIfExists(classFile);
            }
        }
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

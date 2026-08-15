package org.axl.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class Paths {

    static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

    private Paths() {}

    /** Reports a source directory that is missing or a file. */
    static boolean isDirectory(Path dir) {
        if (Files.isDirectory(dir)) {
            return true;
        }
        String problem = Files.exists(dir) ? "not a directory: " : "no such directory: ";
        String hint = dir.toString().endsWith(".axl") && Files.exists(dir)
                ? "; give the source directory that holds the package directories, such as src" : "";
        System.err.println(Main.withEncodingHint("axl: " + problem + dir + hint, dir.toString()));
        return false;
    }

    /** Reports source directories that hold no .axl or .java file at all, which is most likely the wrong directory. */
    static boolean haveSources(List<Path> dirs) throws IOException {
        for (Path dir : dirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                if (walk.anyMatch(p -> p.toString().endsWith(".axl") || p.toString().endsWith(".java"))) {
                    return true;
                }
            }
        }
        System.err.println("axl: no .axl or .java files in " + String.join(", ", dirs.stream().map(Path::toString).toList()));
        return false;
    }

    /**
     * `axl check src/app` with `package app` in the files: the source directory is one level too deep,
     * which gives no error but other files would not find these classes where Java looks for them.
     */
    static void warnAboutTooDeep(List<Path> dirs) throws IOException {
        for (Path dir : dirs) {
            Path name = dir.toAbsolutePath().normalize().getFileName();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".axl")).toList()) {
                    String declared = declaredPackage(file);
                    if (declared != null && name != null && (declared.equals(name.toString()) || declared.endsWith("." + name))) {
                        System.err.println("axl: warning: " + file + " is in package `" + declared + "`; give the source "
                                + "directory that holds its package directories, such as " + dir.toAbsolutePath().normalize().getParent());
                        return;
                    }
                }
            }
        }
    }

    private static String declaredPackage(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.map(String::strip).filter(l -> l.startsWith("package ")).findFirst()
                    .map(l -> l.substring("package ".length()).strip()).orElse(null);
        } catch (UncheckedIOException e) {
            return null;
        }
    }

    /** Missing entries are not an error, as for javac, but are most likely a typo. */
    static void warnAboutMissingEntries(List<Path> classpath) {
        for (Path entry : classpath) {
            if (!Files.exists(entry)) {
                System.err.println("axl: warning: classpath entry not found: " + entry);
            }
        }
    }

    /**
     * Deletes a temporary directory and everything in it. Several axl processes may delete the same
     * finished work directory at once, so the walk can meet files that are already gone.
     */
    static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | UncheckedIOException e) {
            // Leftovers in the temporary directory do no harm.
        }
    }

    static Path workingDirectory() {
        return Path.of("").toAbsolutePath();
    }

    /** Paths under the working directory are shown relative to it, like javac does. */
    static String display(Path workingDirectory, Path path) {
        Path absolute = workingDirectory.resolve(path).normalize();
        return absolute.startsWith(workingDirectory) ? workingDirectory.relativize(absolute).toString() : absolute.toString();
    }

    /** Files of the temporary directories of `axl run` and `axl check`, which may lie under a source directory. */
    static boolean isInWorkDirectory(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(TEMP) || absolute.equals(TEMP)) {
            return false;
        }
        return WorkDirectories.isWorkDirectory(TEMP.resolve(TEMP.relativize(absolute).getName(0)));
    }
}

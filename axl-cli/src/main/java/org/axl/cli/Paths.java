package org.axl.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class Paths {

    static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

    private Paths() {}

    /** Reports a source directory that is missing or a file. */
    static boolean isDirectory(Path dir) {
        if (Files.isDirectory(dir)) {
            return true;
        }
        System.err.println("axl: " + (Files.exists(dir) ? "not a directory: " : "no such directory: ") + dir);
        return false;
    }

    /** Deletes a temporary directory and everything in it. */
    static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // Leftovers in the temporary directory do no harm.
        }
    }

    /** Paths under the working directory are shown relative to it, like javac does. */
    static String display(Path path) {
        Path cwd = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : absolute.toString();
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

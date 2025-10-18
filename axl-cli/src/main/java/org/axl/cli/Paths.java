package org.axl.cli;

import java.nio.file.Path;

final class Paths {

    private Paths() {}

    /** Paths under the working directory are shown relative to it, like javac does. */
    static String display(Path path) {
        Path cwd = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : absolute.toString();
    }
}

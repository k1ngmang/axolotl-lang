package org.axl.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit that is translated and compiled together: .axl source roots, Java source roots and the
 * classpath, as described by an `axl.json` file or guessed from the directory layout.
 */
record Module(Path root, List<Path> axlRoots, List<Path> javaRoots, List<Path> classpath, boolean strictNull) {

    /** Where javac looks for Java sources: the Java source roots, then the .axl ones, which may hold Java files too. */
    List<Path> sourcepath() {
        List<Path> result = new ArrayList<>(javaRoots);
        axlRoots.stream().filter(root -> !result.contains(root)).forEach(result::add);
        return result;
    }

    /** The source root that contains file, or null. */
    Path axlRootOf(Path file) {
        return axlRoots.stream().filter(file::startsWith).findFirst().orElse(null);
    }
}

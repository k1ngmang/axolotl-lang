package org.axl.lsp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A unit that is translated and compiled together: .axl source roots, Java source roots and the
 * classpath, as described by an `axl.json` file or guessed from the directory layout. The Java
 * sourcepath holds the Java sources of the modules it depends on, which it reads but does not compile.
 */
record Module(Path root, List<Path> axlRoots, List<Path> javaRoots, List<Path> javaSourcepath, List<Path> classpath,
              boolean strictNull) {

    /**
     * Where javac looks for Java sources: the Java source roots, then the .axl ones, which may hold
     * Java files too, then the Java sourcepath.
     */
    List<Path> sourcepath() {
        List<Path> result = new ArrayList<>(javaRoots);
        axlRoots.stream().filter(root -> !result.contains(root)).forEach(result::add);
        javaSourcepath.stream().filter(root -> !result.contains(root)).forEach(result::add);
        return result;
    }

    /**
     * The javac options for the Java sourcepath: the classes it needs come from there when the
     * sources are newer than the class files, and are not compiled on their own.
     */
    List<String> javaSourcepathOptions() {
        if (javaSourcepath.isEmpty()) {
            return List.of();
        }
        String path = javaSourcepath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        return List.of("-sourcepath", path, "-implicit:none");
    }

    /** The source root that contains file, or null. */
    Path axlRootOf(Path file) {
        return axlRoots.stream().filter(file::startsWith).findFirst().orElse(null);
    }
}

package org.axl.cli;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The part of `axl check`, `axl build` and `axl run` that needs the compiler. The commands check their
 * arguments first, with the paths as given; workingDirectory is where relative paths start and what
 * diagnostics show paths relative to.
 */
record CompileJob(Kind kind, List<Path> sourceDirs, @Nullable Path file, Path generatedDir, Path classesDir,
                  List<Path> classpath, boolean nullChecks, boolean strictNull, boolean inlineElvis, boolean smap,
                  Path workingDirectory) {

    enum Kind { CHECK, BUILD, RUN }

    /** What the job leaves to the command: whether it compiled, and for `run` the class to start. */
    record Result(boolean success, @Nullable String mainClass) {}
}

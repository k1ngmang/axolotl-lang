package org.axl.cli;

import static org.axl.cli.DaemonProtocol.readString;
import static org.axl.cli.DaemonProtocol.writeString;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The part of `axl check`, `axl build` and `axl run` that needs the compiler, as one value that can be
 * sent to the compiler daemon. The commands check their arguments first, with the paths as given;
 * workingDirectory is where relative paths start and what diagnostics show paths relative to.
 */
record CompileJob(Kind kind, List<Path> sourceDirs, @Nullable Path file, Path generatedDir, Path classesDir,
                  List<Path> classpath, boolean nullChecks, boolean checkJava, boolean strictNull, boolean inlineElvis,
                  boolean smap, Path workingDirectory) {

    enum Kind { CHECK, BUILD, RUN }

    /** What the job leaves to the command: whether it compiled, and for `run` the class to start. */
    record Result(boolean success, @Nullable String mainClass) {}

    /**
     * The same job with absolute paths, for a process with another working directory. The source directories
     * and the file keep the form they were written in, which diagnostics show their paths in.
     */
    CompileJob resolved() {
        return new CompileJob(kind, sourceDirs, file, workingDirectory.resolve(generatedDir), workingDirectory.resolve(classesDir),
                resolve(classpath), nullChecks, checkJava, strictNull, inlineElvis, smap, workingDirectory);
    }

    List<Path> resolve(List<Path> paths) {
        return paths.stream().map(workingDirectory::resolve).toList();
    }

    /**
     * A path of a diagnostic: absolute when a source directory or the file was written absolute, else relative
     * to the working directory where it lies under it, as javac shows paths.
     */
    String display(Path path) {
        boolean absolute = sourceDirs.stream().anyMatch(Path::isAbsolute) || file != null && file.isAbsolute();
        return absolute ? workingDirectory.resolve(path).normalize().toString() : Paths.display(workingDirectory, path);
    }

    void write(DataOutputStream out) throws IOException {
        writeString(out, kind.name());
        writePaths(out, sourceDirs);
        writeString(out, file == null ? "" : file.toString());
        writeString(out, generatedDir.toString());
        writeString(out, classesDir.toString());
        writePaths(out, classpath);
        out.writeBoolean(nullChecks);
        out.writeBoolean(checkJava);
        out.writeBoolean(strictNull);
        out.writeBoolean(inlineElvis);
        out.writeBoolean(smap);
        writeString(out, workingDirectory.toString());
    }

    static CompileJob read(DataInputStream in) throws IOException {
        Kind kind = Kind.valueOf(readString(in));
        List<Path> sourceDirs = readPaths(in);
        String file = readString(in);
        return new CompileJob(kind, sourceDirs, file.isEmpty() ? null : Path.of(file), Path.of(readString(in)),
                Path.of(readString(in)), readPaths(in), in.readBoolean(), in.readBoolean(), in.readBoolean(),
                in.readBoolean(), in.readBoolean(), Path.of(readString(in)));
    }

    private static void writePaths(DataOutputStream out, List<Path> paths) throws IOException {
        out.writeInt(paths.size());
        for (Path path : paths) {
            writeString(out, path.toString());
        }
    }

    private static List<Path> readPaths(DataInputStream in) throws IOException {
        int size = in.readInt();
        List<Path> paths = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            paths.add(Path.of(readString(in)));
        }
        return paths;
    }
}

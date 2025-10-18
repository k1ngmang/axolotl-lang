package org.axl.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.axl.compiler.AxlDiagnostic;
import org.axl.javac.AxlBuild;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "build", description = "Translate and compile a source directory with .axl and .java files.")
final class BuildCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source directory.")
    Path sourceDir;

    @Option(names = "-d", required = true, description = "Output directory for classes.")
    Path classesDir;

    @Option(names = "-s", description = "Output directory for generated .java and .axlmap files (default: the -d directory).")
    Path generatedDir;

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}", description = "Extra classpath entries.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Override
    public Integer call() throws Exception {
        AxlBuild.Result result = new AxlBuild(sourceDir, generatedDir != null ? generatedDir : classesDir, classesDir)
                .classpath(classpath)
                .nullChecks(!noNullChecks)
                .display(Paths::display)
                .run();
        report(result.diagnostics());
        return result.success() ? 0 : 1;
    }

    static void report(List<AxlDiagnostic> diagnostics) {
        diagnostics.forEach(d -> System.err.println(d.format()));
    }
}

package org.axl.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Options;
import org.axl.javac.AxlBuild;
import org.axl.smap.SmapInjector;
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

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Option(names = "--no-smap", description = "Do not add SMAP line information for debuggers to the classes.")
    boolean noSmap;

    @Override
    public Integer call() throws Exception {
        if (!Paths.isDirectory(sourceDir)) {
            return 2;
        }
        Path generated = generatedDir != null ? generatedDir : classesDir;
        StaleOutputs.remove(sourceDir, generated, classesDir);
        AxlBuild.Result result = new AxlBuild(sourceDir, generated, classesDir)
                .classpath(classpath)
                .nullChecks(!noNullChecks)
                .strictNull(strictNull)
                .options(new Options(inlineElvis))
                .display(Paths::display)
                .run();
        report(result.diagnostics());
        StaleOutputs.record(sourceDir, generated);
        if (result.success() && !noSmap) {
            SmapInjector.inject(classesDir, generated);
        }
        return result.success() ? 0 : 1;
    }

    static void report(List<AxlDiagnostic> diagnostics) {
        diagnostics.forEach(d -> System.err.println(d.format()));
    }
}

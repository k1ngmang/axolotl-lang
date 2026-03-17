package org.axl.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.axl.compiler.Options;
import org.axl.javac.AxlBuild;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "check", description = {
        "Translate and compile source directories without keeping any output; print the errors.",
        "Used by editors: every source directory may hold .axl and .java files."})
final class CheckCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", description = "Source directories.")
    List<Path> sourceDirs = new ArrayList<>();

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}", description = "Classpath of the sources.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Override
    public Integer call() throws Exception {
        if (!sourceDirs.stream().allMatch(Paths::isDirectory)) {
            return 2;
        }
        Paths.warnAboutMissingEntries(classpath);
        Path work = WorkDirectories.create(WorkDirectories.CHECK);
        try {
            AxlBuild.Result result = new AxlBuild(sourceDirs, work.resolve("generated"), work.resolve("classes"))
                    .classpath(classpath)
                    .nullChecks(!noNullChecks)
                    .strictNull(strictNull)
                    .options(new Options(inlineElvis))
                    .display(p -> p.toAbsolutePath().toString())
                    .skip(Paths::isInWorkDirectory)
                    .run();
            result.diagnostics().forEach(d -> System.out.println(d.format()));
            return result.success() ? 0 : 1;
        } finally {
            WorkDirectories.delete(work);
        }
    }
}

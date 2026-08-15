package org.axl.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "build", description = "Translate and compile a source directory with .axl and .java files.")
final class BuildCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Parameters(index = "0", description = "Source directory.")
    Path sourceDir;

    @Option(names = "-d", required = true, description = "Output directory for classes.")
    Path classesDir;

    @Option(names = "-s", description = "Output directory for generated .java and .axlmap files (default: the -d directory).")
    Path generatedDir;

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}",
            description = "Jars and class directories the code uses, separated by `${sys:path.separator}`; repeatable.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Option(names = "--no-smap", description = {
            "Keep the line numbers of the generated .java files in the classes.",
            "By default the classes name the .axl file and its lines in stack traces and debuggers."})
    boolean noSmap;

    @Override
    public Integer call() throws Exception {
        if (!Paths.isDirectory(sourceDir) || !Paths.haveSources(List.of(sourceDir))) {
            return 2;
        }
        Paths.warnAboutMissingEntries(classpath);
        Paths.warnAboutTooDeep(List.of(sourceDir));
        Path generated = generatedDir != null ? generatedDir : classesDir;
        var job = new CompileJob(CompileJob.Kind.BUILD, List.of(sourceDir), null, generated, classesDir, classpath,
                !noNullChecks, strictNull, inlineElvis, !noSmap, Paths.workingDirectory());
        return main.compiler().compile(job).success() ? 0 : 1;
    }
}

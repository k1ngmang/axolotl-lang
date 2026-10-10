package org.axl.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "check", description = {
        "Translate and compile source directories without keeping any output; print the errors.",
        "Used by editors: every source directory may hold .axl and .java files."})
final class CheckCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Parameters(arity = "1..*", description = "Source directories.")
    List<Path> sourceDirs = new ArrayList<>();

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}",
            description = "Jars and class directories the code uses, separated by `${sys:path.separator}`; repeatable.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Option(names = "--check-java", description = {
            "Run Error Prone and NullAway on the .java files of the source directories too.",
            "By default they check only the Java translated from Axolotl."})
    boolean checkJava;

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Option(names = "--no-param-checks", description = {
            "Leave out the checks that Java callers pass no null for a non-null parameter.",
            "By default public functions and constructors check them on entry."})
    boolean noParamChecks;

    @Override
    public Integer call() throws Exception {
        if (!sourceDirs.stream().allMatch(Paths::isDirectory) || !Paths.haveSources(sourceDirs)) {
            return 2;
        }
        Paths.warnAboutMissingEntries(classpath);
        Paths.warnAboutTooDeep(sourceDirs);
        Path work = WorkDirectories.create(WorkDirectories.CHECK);
        try {
            var job = new CompileJob(CompileJob.Kind.CHECK, sourceDirs, null, work.resolve("generated"),
                    work.resolve("classes"), classpath, !noNullChecks, checkJava, strictNull, inlineElvis, !noParamChecks, false,
                    Paths.workingDirectory());
            return main.compiler().compile(job).success() ? 0 : 1;
        } finally {
            WorkDirectories.delete(work);
        }
    }
}

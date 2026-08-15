package org.axl.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;
import org.axl.javac.AxlBuild;
import org.axl.smap.SmapInjector;

/** Compiles in this JVM. The only class of the CLI that uses the compiler. */
final class LocalCompiler implements Compiler {

    static final LocalCompiler INSTANCE = new LocalCompiler();

    private LocalCompiler() {}

    @Override
    public CompileJob.Result compile(CompileJob job) throws IOException {
        return switch (job.kind()) {
            case CHECK -> check(job);
            case BUILD -> build(job);
            case RUN -> run(job);
        };
    }

    private static CompileJob.Result check(CompileJob job) throws IOException {
        Path cwd = job.workingDirectory();
        AxlBuild.Result result = configure(new AxlBuild(job.sourceDirs(), job.generatedDir(), job.classesDir()), job)
                .display(p -> cwd.resolve(p).toString())
                .skip(Paths::isInWorkDirectory)
                .run();
        result.diagnostics().forEach(d -> System.out.println(format(d)));
        return new CompileJob.Result(result.success(), null);
    }

    private static CompileJob.Result build(CompileJob job) throws IOException {
        Path sourceDir = job.sourceDirs().getFirst();
        StaleOutputs.remove(sourceDir, job.generatedDir(), job.classesDir());
        AxlBuild.Result result = configure(new AxlBuild(sourceDir, job.generatedDir(), job.classesDir()), job)
                .display(p -> Paths.display(job.workingDirectory(), p))
                .run();
        report(result.diagnostics());
        StaleOutputs.record(sourceDir, job.generatedDir());
        if (result.success() && job.smap()) {
            SmapInjector.inject(job.classesDir(), job.generatedDir());
        }
        return new CompileJob.Result(result.success(), null);
    }

    /** The command has checked that the file is UTF-8 text with the .axl extension. */
    private static CompileJob.Result run(CompileJob job) throws IOException {
        Path file = job.file();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String className = Translator.className(file.getFileName().toString());
        SourceFile parsed = SourceParser.parse(file.toString(), className, text).file();
        String packageName = parsed != null && parsed.packageName() != null ? parsed.packageName() : "";
        Path root = RunCommand.sourceRoot(job.workingDirectory().resolve(file).normalize(), packageName);
        AxlBuild.Result result = configure(new AxlBuild(root, job.generatedDir(), job.classesDir()), job)
                .display(p -> Paths.display(job.workingDirectory(), p))
                .skip(Paths::isInWorkDirectory)
                .run();
        report(result.diagnostics());
        return new CompileJob.Result(result.success(), packageName.isEmpty() ? className : packageName + "." + className);
    }

    private static AxlBuild configure(AxlBuild build, CompileJob job) {
        return build.classpath(job.classpath())
                .nullChecks(job.nullChecks())
                .strictNull(job.strictNull())
                .options(new Options(job.inlineElvis()));
    }

    private static void report(List<AxlDiagnostic> diagnostics) {
        diagnostics.forEach(d -> System.err.println(format(d)));
    }

    /** A diagnostic as printed, with the code of an .axl diagnostic to look up in the documentation. */
    private static String format(AxlDiagnostic diagnostic) {
        return diagnostic.file().endsWith(".axl") ? diagnostic.format() + " [" + diagnostic.code().id() + "]" : diagnostic.format();
    }
}

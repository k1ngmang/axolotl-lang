package org.axl.cli;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;
import org.axl.compiler.Options;
import org.axl.javac.AxlBuild;
import org.axl.smap.Retracer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "Compile the source tree of an .axl file and run its main function.")
final class RunCommand implements Callable<Integer> {

    /** Set once the program's main has run, whose threads may outlive it. */
    static volatile boolean programRan;

    @Parameters(index = "0", description = "The .axl file with `fn main()`.")
    Path file;

    @Parameters(index = "1..*", description = "Arguments for the program, passed unchanged, options included.")
    List<String> args = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(file)) {
            System.err.println("axl: no such file: " + file);
            return 2;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException(file + " is not UTF-8 text", e);
        }
        String className = Translator.className(file.getFileName().toString());
        String packageName = packageOf(className, text);
        Path root = sourceRoot(file.toAbsolutePath().normalize(), packageName);
        Path work = Files.createTempDirectory("axl-run");
        // The program's threads may load classes from it until the JVM ends.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Paths.deleteRecursively(work)));
        AxlBuild.Result result = new AxlBuild(root, work.resolve("generated"), work.resolve("classes"))
                .display(Paths::display)
                .nullChecks(!noNullChecks)
                .strictNull(strictNull)
                .options(new Options(inlineElvis))
                .run();
        BuildCommand.report(result.diagnostics());
        if (!result.success()) {
            return 1;
        }
        String mainClass = packageName.isEmpty() ? className : packageName + "." + className;
        return runMain(work.resolve("classes"), work.resolve("generated"), mainClass);
    }

    private String packageOf(String className, String text) {
        SourceFile parsed = SourceParser.parse(file.toString(), className, text).file();
        return parsed != null && parsed.packageName() != null ? parsed.packageName() : "";
    }

    /** The directory the package path starts from; the file's own directory if they do not match. */
    static Path sourceRoot(Path file, String packageName) {
        Path dir = file.getParent();
        if (packageName.isEmpty()) {
            return dir;
        }
        Path expected = Path.of("", packageName.split("\\."));
        return dir.endsWith(expected) ? dir.getRoot().resolve(dir.subpath(0, dir.getNameCount() - expected.getNameCount())) : dir;
    }

    private int runMain(Path classes, Path generated, String mainClass) throws IOException {
        // Not closed: threads the program starts may still load classes after main returns.
        var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        Method main;
        try {
            main = loader.loadClass(mainClass).getMethod("main", String[].class);
        } catch (ReflectiveOperationException e) {
            System.err.println("axl: " + file + " has no `fn main()`");
            return 1;
        }
        Thread.currentThread().setContextClassLoader(loader);
        try {
            main.invoke(null, (Object) args.toArray(String[]::new));
            programRan = true;
            return 0;
        } catch (InvocationTargetException e) {
            report(e.getCause(), generated);
            return 1;
        } catch (ExceptionInInitializerError e) {
            report(e, generated);
            return 1;
        } catch (IllegalAccessException e) {
            System.err.println("axl: " + file + " has no `fn main()`");
            return 1;
        }
    }

    /** The program's stack trace at .axl lines, without the frames of this tool below its main. */
    private static void report(Throwable failure, Path generated) {
        Retracer.of(List.of(generated), List.of()).retrace(failure);
        for (Throwable t = failure; t != null; t = t.getCause()) {
            StackTraceElement[] frames = t.getStackTrace();
            int end = 0;
            while (end < frames.length && !frames[end].getClassName().startsWith("jdk.internal.reflect.")
                    && !frames[end].getClassName().equals("java.lang.reflect.Method")) {
                end++;
            }
            t.setStackTrace(Arrays.copyOf(frames, end));
        }
        failure.printStackTrace();
    }
}

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
import org.axl.smap.Retracer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "run", description = "Compile the source tree of an .axl file and run its main function.")
final class RunCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    /** Set once the program's main has run, whose threads may outlive it. */
    static volatile boolean programRan;

    @Parameters(index = "0", description = "The .axl file with `fn main()`.")
    Path file;

    @Parameters(index = "1..*", description = "Arguments for the program, passed unchanged, options included.")
    List<String> args = new ArrayList<>();

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}",
            description = "Jars and class directories the code uses, separated by `${sys:path.separator}`; repeatable.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--no-null-checks", description = "Skip Error Prone and NullAway.")
    boolean noNullChecks;

    @Option(names = "--strict-null", description = "Treat values from Java code without nullness annotations as nullable.")
    boolean strictNull;

    @Option(names = "--inline-elvis", description = "Expand `?:` at statement level without a lambda.")
    boolean inlineElvis;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(file)) {
            System.err.println(Main.withEncodingHint("axl: no such file: " + file, file.toString()));
            return 2;
        }
        if (!file.getFileName().toString().endsWith(".axl")) {
            System.err.println("axl: not an .axl file: " + file);
            return 2;
        }
        try {
            Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException(file + " is not UTF-8 text", e);
        }
        Paths.warnAboutMissingEntries(classpath);
        Path work = WorkDirectories.create(WorkDirectories.RUN);
        var job = new CompileJob(CompileJob.Kind.RUN, List.of(), file, work.resolve("generated"), work.resolve("classes"),
                classpath, !noNullChecks, strictNull, inlineElvis, false, Paths.workingDirectory());
        CompileJob.Result result = main.compiler().compile(job);
        if (!result.success()) {
            return 1;
        }
        return runMain(work.resolve("classes"), work.resolve("generated"), result.mainClass());
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
        // The parent loader has the Axolotl runtime; the libraries of -cp come after the program's own classes.
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (Path entry : classpath) {
            urls.add(entry.toUri().toURL());
        }
        // Not closed: threads the program starts may still load classes after main returns.
        var loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        Method main;
        try {
            main = loader.loadClass(mainClass).getMethod("main", String[].class);
        } catch (ClassNotFoundException e) {
            System.err.println("axl: " + file + " has no `fn main()`");
            return 1;
        } catch (NoSuchMethodException e) {
            System.err.println("axl: " + file + noMainReason(loader, mainClass));
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

    /** `fn main(args: List<String>)` and `fn main() -> Int` compile to ordinary functions that Java cannot start. */
    private static String noMainReason(ClassLoader loader, String mainClass) {
        try {
            Method[] methods = loader.loadClass(mainClass).getMethods();
            if (Arrays.stream(methods).anyMatch(m -> m.getName().equals("main") && m.getParameterCount() > 0)) {
                return ": `fn main` must have no parameters; the program's arguments are in `args`, a String[]";
            }
            if (Arrays.stream(methods).anyMatch(m -> m.getName().equals("main"))) {
                return ": `fn main` must have no result type; remove `->` and the type, and end the program with `System.exit(code)`";
            }
        } catch (ClassNotFoundException e) {
            // Reported as a missing main below.
        }
        return " has no `fn main()`";
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

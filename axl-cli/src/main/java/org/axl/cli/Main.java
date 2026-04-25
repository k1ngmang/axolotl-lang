package org.axl.cli;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ScopeType;

// INHERIT gives every subcommand --help and --version too.
@Command(name = "axl", mixinStandardHelpOptions = true, scope = ScopeType.INHERIT, versionProvider = Main.Version.class,
        description = "Axolotl compiler: translates .axl files to Java and compiles them.",
        subcommands = {BuildCommand.class, RunCommand.class, CheckCommand.class, RetraceCommand.class, SetupCommand.class,
                HelpCommand.class})
public final class Main {

    public static void main(String[] args) {
        utf8WithoutLocale();
        int code = commandLine().execute(args);
        // A program started by `axl run` may still have threads running; the JVM ends when they do.
        if (code != 0 || !RunCommand.programRan) {
            System.exit(code);
        }
    }

    /** Without a locale Java writes ASCII, which turns every other character of a name or message into `?`. */
    private static void utf8WithoutLocale() {
        try {
            if (!Charset.forName(System.getProperty("stdout.encoding", "UTF-8")).equals(StandardCharsets.US_ASCII)) {
                return;
            }
        } catch (IllegalArgumentException e) {
            return;
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new Main());
        // Everything after the .axl file belongs to the program, options included.
        commandLine.getSubcommands().get("run").setStopAtPositional(true);
        // picocli's own converter hides the InvalidPathException in a message.
        commandLine.registerConverter(Path.class, text -> Path.of(text));
        CommandLine.IParameterExceptionHandler usage = commandLine.getParameterExceptionHandler();
        commandLine.setParameterExceptionHandler((e, args) -> {
            if (!(e.getCause() instanceof InvalidPathException invalid)) {
                return usage.handleParseException(e, args);
            }
            e.getCommandLine().getErr().println(invalidPathMessage(invalid));
            return 2;
        });
        commandLine.setExecutionExceptionHandler((e, cl, parsed) -> {
            Exception cause = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
            if (!(cause instanceof IOException)) {
                throw e;
            }
            String message = cause instanceof NoSuchFileException ? "no such file: " + cause.getMessage() : cause.getMessage();
            cl.getErr().println(withEncodingHint("axl: " + message, String.valueOf(cause.getMessage())));
            return 2;
        });
        return commandLine;
    }

    /** The version of the jar, which classes outside a jar do not have. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] {"axl " + (version != null ? version : "(development build)")};
        }
    }

    /** Usually a non-ASCII file name under a locale whose encoding cannot represent it. */
    static String invalidPathMessage(InvalidPathException e) {
        String message = "axl: invalid path: " + e.getReason();
        return nonUtf8FileNames() ? message + encodingHint() : message;
    }

    /**
     * Adds the hint of {@link #invalidPathMessage} when the path or the working directory has characters
     * that the locale's encoding may have garbled: under LANG=C a non-ASCII directory reads as `??`,
     * and every relative path in it is then "missing".
     */
    static String withEncodingHint(String message, String path) {
        boolean suspicious = mayBeGarbled(path) || mayBeGarbled(System.getProperty("user.dir", ""));
        return suspicious && nonUtf8FileNames() && File.separatorChar == '/' ? message + encodingHint() : message;
    }

    private static boolean mayBeGarbled(String text) {
        return text.chars().anyMatch(c -> c > 127 || c == '?');
    }

    private static boolean nonUtf8FileNames() {
        return !System.getProperty("sun.jnu.encoding", "").equalsIgnoreCase("UTF-8");
    }

    private static String encodingHint() {
        return " (file names are read as " + System.getProperty("sun.jnu.encoding")
                + "; a UTF-8 locale such as LANG=C.UTF-8 avoids this)";
    }
}

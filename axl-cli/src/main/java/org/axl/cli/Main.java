package org.axl.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "axl", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = "Axolotl compiler: translates .axl files to Java and compiles them.",
        subcommands = {BuildCommand.class, RunCommand.class, CheckCommand.class, RetraceCommand.class, SetupCommand.class})
public final class Main {

    public static void main(String[] args) {
        int code = commandLine().execute(args);
        // A program started by `axl run` may still have threads running; the JVM ends when they do.
        if (code != 0 || !RunCommand.programRan) {
            System.exit(code);
        }
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
            cl.getErr().println("axl: " + (cause instanceof NoSuchFileException ? "no such file: " : "") + cause.getMessage());
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
        String encoding = System.getProperty("sun.jnu.encoding", "");
        if (!encoding.equalsIgnoreCase("UTF-8")) {
            message += " (file names are read as " + encoding + "; a UTF-8 locale such as LANG=C.UTF-8 avoids this)";
        }
        return message;
    }
}

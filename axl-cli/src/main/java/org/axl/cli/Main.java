package org.axl.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "axl", mixinStandardHelpOptions = true, version = "axl 0.1.0",
        description = "Axolotl compiler: translates .axl files to Java and compiles them.",
        subcommands = {BuildCommand.class, RunCommand.class, CheckCommand.class, RetraceCommand.class})
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
}

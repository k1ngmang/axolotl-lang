package org.axl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "axl", mixinStandardHelpOptions = true, version = "axl 0.1.0",
        description = "Axolotl compiler: translates .axl files to Java and compiles them.",
        subcommands = {BuildCommand.class, RunCommand.class, CheckCommand.class, RetraceCommand.class})
public final class Main {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}

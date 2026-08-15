package org.axl.cli;

import java.io.IOException;

/** Runs the compile jobs of the commands: in this JVM, or in the compiler daemon. */
interface Compiler {

    /** Prints the diagnostics to System.out for `check` and to System.err otherwise, as the command would. */
    CompileJob.Result compile(CompileJob job) throws IOException;
}

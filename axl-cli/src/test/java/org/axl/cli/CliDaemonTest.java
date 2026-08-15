package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The tests of CliTest with the jobs compiled in a daemon: the output and the exit codes must not change. */
class CliDaemonTest extends CliTest {

    @TempDir
    static Path daemons;

    @Override
    CommandLine commandLine() {
        return Main.commandLine(settings());
    }

    private static DaemonSettings settings() {
        return new DaemonSettings(true, daemons, Duration.ofMinutes(10), "cli-test");
    }

    /** A job that fails in the daemon is compiled in the test's JVM again, which the tests would not notice. */
    @AfterAll
    static void stopDaemon() {
        PrintStream original = System.out;
        var status = new ByteArrayOutputStream();
        System.setOut(new PrintStream(status, true, StandardCharsets.UTF_8));
        try {
            Main.commandLine(settings()).execute("daemon", "--status");
        } finally {
            System.setOut(original);
        }
        Main.commandLine(settings()).execute("daemon", "--stop");
        assertTrue(status.toString(StandardCharsets.UTF_8).matches("(?s).*, [1-9]\\d+ jobs;.*"), status.toString());
    }
}

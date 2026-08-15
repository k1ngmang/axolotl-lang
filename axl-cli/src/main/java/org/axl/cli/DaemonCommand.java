package org.axl.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "daemon", description = {
        "Show or stop the compiler daemons.",
        "check, build and run compile in a daemon that keeps the compiler loaded; the first of them starts it,"
                + " and it ends after 3 hours without work (AXL_DAEMON_IDLE, such as 30m). --no-daemon or"
                + " AXL_DAEMON=off compile in the command's own process."})
final class DaemonCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Option(names = "--status", description = "Show the running daemons of every installed version (the default).")
    boolean status;

    @Option(names = "--stop", description = "Stop the running daemons of every installed version.")
    boolean stop;

    @Override
    public Integer call() {
        int found = 0;
        for (Path dir : DaemonFiles.directories(main.daemonSettings().baseDir())) {
            DaemonFiles.Entry entry = DaemonFiles.read(dir);
            if (entry == null) {
                continue;
            }
            String answer = DaemonProtocol.ask(entry, stop ? DaemonProtocol.STOP : DaemonProtocol.STATUS);
            if (answer == null) {
                continue;
            }
            found++;
            if (stop) {
                awaitExit(entry.pid());
                System.out.println("Stopped the axl " + entry.version() + " daemon (pid " + entry.pid() + ")");
            } else {
                System.out.println(answer);
            }
        }
        if (found == 0) {
            System.out.println("No axl daemon is running");
        }
        return 0;
    }

    /** An installer that replaces axl needs the daemon's jars closed. */
    private static void awaitExit(long pid) {
        ProcessHandle.of(pid).ifPresent(process -> {
            try {
                process.onExit().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // Reported as stopped: it no longer takes jobs.
            }
        });
    }
}

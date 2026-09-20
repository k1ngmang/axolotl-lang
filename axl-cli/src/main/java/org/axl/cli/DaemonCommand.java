package org.axl.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
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

    @Option(names = "--status",
            description = "Show the running daemons of every installation, with the directory each belongs to (the default).")
    boolean status;

    @Option(names = "--stop", description = "Stop the running daemons of this installation of axl.")
    boolean stop;

    @Option(names = "--all", description = "With --stop: stop the daemons of every installation and version.")
    boolean all;

    @Override
    public Integer call() {
        DaemonSettings settings = main.daemonSettings();
        int found = 0;
        int others = 0;
        for (Path dir : DaemonFiles.directories(settings.baseDir())) {
            DaemonFiles.Entry entry = DaemonFiles.read(dir);
            if (entry == null) {
                continue;
            }
            Path installation = installation(entry);
            boolean own = dir.equals(settings.directory()) || settings.installation().equals(installation);
            if (stop && !all && !own) {
                if (ProcessHandle.of(entry.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                    others++;
                }
                continue;
            }
            String answer = DaemonProtocol.ask(entry, stop ? DaemonProtocol.STOP : DaemonProtocol.STATUS);
            if (answer == null) {
                continue;
            }
            found++;
            if (stop) {
                awaitExit(entry.pid());
                String of = installation != null ? " of " + installation : "";
                System.out.println("Stopped the axl " + entry.version() + " daemon" + of + " (pid " + entry.pid() + ")");
            } else {
                System.out.println(answer);
                String where = installation != null ? installation.toString() : "unknown (an earlier build of axl)";
                System.out.println("  installation: " + where + (own ? " (this one)" : ""));
            }
        }
        if (found == 0) {
            System.out.println(others == 0 ? "No axl daemon is running" : "No axl daemon of this installation is running");
        }
        if (others > 0) {
            System.out.println(others + (others == 1 ? " daemon" : " daemons") + " of other installations left running;"
                    + " axl daemon --stop --all stops them too");
        }
        return 0;
    }

    /**
     * The installation a daemon belongs to. The entries of earlier builds do not name it; their daemon was
     * started with the class path of its installation. Java on Linux gives the arguments of a command
     * line longer than 4096 bytes only as its text, cut there, which still starts with the class path.
     */
    static @Nullable Path installation(DaemonFiles.Entry entry) {
        if (entry.installation() != null) {
            return Path.of(entry.installation());
        }
        String line = ProcessHandle.of(entry.pid()).flatMap(p -> p.info().commandLine()).orElse("");
        int option = line.indexOf(" -cp ");
        if (option < 0) {
            return null;
        }
        String classPath = line.substring(option + " -cp ".length());
        int end = classPath.indexOf(" " + Daemon.class.getName() + " ");
        return DaemonSettings.installationOf(end >= 0 ? classPath.substring(0, end) : classPath);
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

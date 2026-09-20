package org.axl.cli;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.axl.javac.JavaCompilation;
import org.jspecify.annotations.Nullable;

/**
 * Hands compile jobs to the daemon of this installation, starting it when none runs, and compiles in
 * this JVM whenever the daemon cannot do it: no Java executable to start it with, a daemon that does
 * not start or answer, or a job that fails in the daemon. In this JVM the job then prints exactly what
 * it would have printed without a daemon.
 */
final class DaemonClient implements Compiler {

    private static final long START_TIMEOUT_MILLIS = 30_000;

    private final DaemonSettings settings;
    private final boolean verbose;

    DaemonClient(DaemonSettings settings, boolean verbose) {
        this.settings = settings;
        this.verbose = verbose;
    }

    @Override
    public CompileJob.Result compile(CompileJob job) throws IOException {
        CompileJob.Result result = null;
        try {
            result = inDaemon(job);
        } catch (IOException | RuntimeException e) {
            note("compiling in this process: the compiler daemon failed: " + e.getMessage());
        }
        return result != null ? result : LocalCompiler.INSTANCE.compile(job);
    }

    private CompileJob.@Nullable Result inDaemon(CompileJob job) throws IOException {
        Path dir = settings.directory();
        DaemonProtocol.Connection running = connect(dir);
        DaemonProtocol.Connection connection = running != null ? running : start(dir);
        if (connection == null) {
            return null;
        }
        try (connection) {
            job.resolved().write(connection.out());
            connection.out().flush();
            connection.socket().setSoTimeout(0);
            // Printed only once the job is done: a job that fails there is compiled here again.
            List<Byte> streams = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            while (true) {
                int kind = connection.in().read();
                switch (kind) {
                    case DaemonProtocol.STDOUT, DaemonProtocol.STDERR -> {
                        streams.add((byte) kind);
                        texts.add(DaemonProtocol.readString(connection.in()));
                    }
                    case DaemonProtocol.RESULT -> {
                        boolean success = connection.in().readBoolean();
                        String mainClass = DaemonProtocol.readString(connection.in());
                        for (int i = 0; i < texts.size(); i++) {
                            (streams.get(i) == DaemonProtocol.STDOUT ? System.out : System.err).print(texts.get(i));
                        }
                        System.out.flush();
                        System.err.flush();
                        return new CompileJob.Result(success, mainClass.isEmpty() ? null : mainClass);
                    }
                    case DaemonProtocol.FAILED -> {
                        note("compiling in this process: " + DaemonProtocol.readString(connection.in()));
                        return null;
                    }
                    default -> throw new IOException("the connection ended");
                }
            }
        }
    }

    /** A connection to the running daemon of this installation, or null. */
    private DaemonProtocol.@Nullable Connection connect(Path dir) {
        DaemonFiles.Entry entry = DaemonFiles.read(dir);
        if (entry == null) {
            return null;
        }
        DaemonProtocol.Connection connection = DaemonProtocol.open(entry, DaemonProtocol.COMPILE, settings.fingerprint());
        if (connection != null) {
            note("compiling in the daemon (pid " + entry.pid() + ")");
        }
        return connection;
    }

    /**
     * Starts the daemon and connects to it. The lock keeps launchers that start at the same time from
     * starting a daemon each; the file lock is held per process, so threads of one JVM take turns first.
     */
    private DaemonProtocol.@Nullable Connection start(Path dir) throws IOException {
        DaemonFiles.createPrivateDirectories(dir);
        synchronized (DaemonClient.class) {
            try (FileChannel channel = FileChannel.open(dir.resolve(DaemonFiles.START_LOCK), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
                DaemonProtocol.Connection connection = connect(dir);
                if (connection != null) {
                    return connection;
                }
                DaemonFiles.removeEnded(settings.baseDir(), dir);
                return startLocked(dir);
            }
        }
    }

    private DaemonProtocol.@Nullable Connection startLocked(Path dir) throws IOException {
        boolean windows = File.separatorChar == '\\';
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        if (!Files.isExecutable(java)) {
            note("compiling in this process: no " + java + " to start the compiler daemon with");
            return null;
        }
        List<String> command = new ArrayList<>(List.of(java.toString()));
        command.addAll(JavaCompilation.ERROR_PRONE_JVM_ARGS);
        command.addAll(List.of(
                "-Dfile.encoding=" + System.getProperty("file.encoding"),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", System.getProperty("java.class.path"),
                Daemon.class.getName(), dir.toString(), settings.fingerprint(), Long.toString(settings.idle().toMillis()),
                settings.installation().toString()));
        Path log = dir.resolve(DaemonFiles.LOG);
        // Without a terminal and without the launcher's pipes, which whoever started axl may wait to be closed.
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? "NUL" : "/dev/null")))
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start();
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            DaemonFiles.Entry entry = DaemonFiles.read(dir);
            if (entry != null && entry.pid() == process.pid()) {
                DaemonProtocol.Connection connection = DaemonProtocol.open(entry, DaemonProtocol.COMPILE, settings.fingerprint());
                note("started the compiler daemon (pid " + entry.pid() + ")");
                if (connection == null) {
                    note("compiling in this process: the new compiler daemon does not answer; see " + log);
                }
                return connection;
            }
            if (!process.isAlive()) {
                note("compiling in this process: the compiler daemon did not start; see " + log);
                return null;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.destroy();
        note("compiling in this process: the compiler daemon did not start in time; see " + log);
        return null;
    }

    private void note(String message) {
        if (verbose) {
            System.err.println("axl: " + message);
        }
    }
}

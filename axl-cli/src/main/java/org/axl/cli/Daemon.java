package org.axl.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * The compiler daemon: a JVM that keeps javac, Error Prone, NullAway and the compiler loaded and
 * compiled by the JIT between commands. Nothing of javac is kept between jobs; each job gets a new
 * compilation, as in a fresh process. Jobs run one at a time, since a job prints through System.out and
 * System.err. The daemon ends after a time without work, when asked to stop, and when its registry
 * entry is gone or names another daemon.
 *
 * Arguments: the daemon's directory, the fingerprint of its installation, the idle time in milliseconds
 * and the directory of the installation.
 */
final class Daemon {

    private final Path dir;
    private final String fingerprint;
    private final long idleMillis;
    private final String installation;
    private final long pid = ProcessHandle.current().pid();
    private final String token = newToken();
    private final long started = System.currentTimeMillis();
    // Fair, so that a stop waits for the jobs that came before it.
    private final ReentrantLock jobs = new ReentrantLock(true);
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger compiled = new AtomicInteger();
    // System.err is the log until a job replaces it.
    private final PrintStream log = System.err;
    private volatile long lastUsed = started;
    private volatile boolean stopping;
    private ServerSocket server;

    private Daemon(Path dir, String fingerprint, long idleMillis, String installation) {
        this.dir = dir;
        this.fingerprint = fingerprint;
        this.idleMillis = idleMillis;
        this.installation = installation;
    }

    public static void main(String[] args) throws IOException {
        new Daemon(Path.of(args[0]), args[1], Long.parseLong(args[2]), args[3]).serve();
    }

    private void serve() throws IOException {
        ignoreTerminalSignals();
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        DaemonFiles.write(dir, new DaemonFiles.Entry(pid, server.getLocalPort(), token, fingerprint, DaemonSettings.version(),
                installation));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> DaemonFiles.deleteIfOwn(dir, pid, token)));
        log("started on port " + server.getLocalPort());
        Thread.ofPlatform().daemon().name("axl-daemon-idle").start(this::watch);
        while (!stopping) {
            try {
                Socket socket = server.accept();
                Thread.ofPlatform().daemon().name("axl-daemon-client").start(() -> serve(socket));
            } catch (IOException e) {
                if (!stopping) {
                    log("cannot accept a connection: " + e);
                }
            }
        }
    }

    /**
     * Started from a terminal, the daemon is in the launcher's process group: Ctrl+C on a program that
     * `axl run` started, or closing the terminal, would end it too.
     */
    private static void ignoreTerminalSignals() {
        for (String name : List.of("INT", "HUP")) {
            try {
                Signal.handle(new Signal(name), SignalHandler.SIG_IGN);
            } catch (IllegalArgumentException e) {
                // Windows has no SIGHUP.
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(DaemonProtocol.ANSWER_TIMEOUT_MILLIS);
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            if (in.readInt() != DaemonProtocol.MAGIC || in.readInt() != DaemonProtocol.VERSION) {
                return;
            }
            byte[] given = DaemonProtocol.readString(in, 1024).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
            switch (DaemonProtocol.readString(in, 1024)) {
                case DaemonProtocol.STATUS -> answer(out, status());
                case DaemonProtocol.STOP -> {
                    answer(out, "stopping");
                    stop("asked to stop");
                }
                case DaemonProtocol.COMPILE -> {
                    if (!DaemonProtocol.readString(in, 1024).equals(fingerprint)) {
                        out.writeByte(DaemonProtocol.MISMATCH);
                        out.flush();
                        return;
                    }
                    out.writeByte(DaemonProtocol.ACCEPTED);
                    out.flush();
                    CompileJob job = CompileJob.read(in);
                    socket.setSoTimeout(0);
                    compile(job, out);
                }
                default -> { }
            }
        } catch (IOException e) {
            log("connection ended: " + e);
        }
    }

    private static void answer(DataOutputStream out, String text) throws IOException {
        out.writeByte(DaemonProtocol.ACCEPTED);
        out.writeByte(DaemonProtocol.TEXT);
        DaemonProtocol.writeString(out, text);
        out.flush();
    }

    private void compile(CompileJob job, DataOutputStream out) throws IOException {
        active.incrementAndGet();
        jobs.lock();
        try {
            if (stopping) {
                fail(out, "the compiler daemon is stopping");
                return;
            }
            Output output = new Output();
            PrintStream stdout = System.out;
            PrintStream stderr = System.err;
            System.setOut(output.stream(DaemonProtocol.STDOUT));
            System.setErr(output.stream(DaemonProtocol.STDERR));
            CompileJob.Result result;
            try {
                result = LocalCompiler.INSTANCE.compile(job);
            } catch (Exception | Error e) {
                log("job failed: " + e);
                e.printStackTrace(log);
                fail(out, "the job failed in the compiler daemon: " + e);
                // After an OutOfMemoryError and the like the JVM may not be fit for another job.
                if (e instanceof Error && !(e instanceof StackOverflowError)) {
                    stop("a job failed with " + e);
                }
                return;
            } finally {
                System.setOut(stdout);
                System.setErr(stderr);
                compiled.incrementAndGet();
            }
            output.writeTo(out);
            out.writeByte(DaemonProtocol.RESULT);
            out.writeBoolean(result.success());
            DaemonProtocol.writeString(out, result.mainClass() != null ? result.mainClass() : "");
            out.flush();
        } finally {
            lastUsed = System.currentTimeMillis();
            jobs.unlock();
            active.decrementAndGet();
        }
    }

    private static void fail(DataOutputStream out, String reason) throws IOException {
        out.writeByte(DaemonProtocol.FAILED);
        DaemonProtocol.writeString(out, reason);
        out.flush();
    }

    private String status() {
        long now = System.currentTimeMillis();
        String state = active.get() > 0 ? "busy" : "idle for " + format(now - lastUsed);
        return "axl " + DaemonSettings.version() + " daemon, pid " + pid + ": up " + format(now - started) + ", " + state
                + ", " + compiled.get() + (compiled.get() == 1 ? " job" : " jobs") + "; stops after " + format(idleMillis)
                + " without work";
    }

    private static String format(long millis) {
        Duration duration = Duration.ofMillis(millis);
        if (duration.toHours() > 0) {
            return duration.toHours() + " h " + duration.toMinutesPart() + " min";
        }
        return duration.toMinutes() > 0 ? duration.toMinutes() + " min" : duration.toSeconds() + " s";
    }

    /** Ends the daemon after the jobs that are already waiting, which compile in their launchers instead. */
    private synchronized void stop(String reason) {
        if (stopping) {
            return;
        }
        stopping = true;
        log("stopping: " + reason);
        // Started first: it keeps the JVM alive once the loop of serve() ends.
        Thread.ofPlatform().name("axl-daemon-stop").start(() -> {
            jobs.lock();
            System.exit(0);
        });
        try {
            server.close();
        } catch (IOException e) {
            // Closing is all that is wanted.
        }
    }

    /**
     * Checks about once a second whether the daemon has been idle long enough or was replaced, by another
     * daemon or by new jars: an upgrade, a reinstallation, or a new build of axl.
     */
    private void watch() {
        long interval = Math.clamp(idleMillis / 4, 50, 1000);
        String installation = DaemonSettings.installedFingerprint();
        long collected = lastUsed;
        while (!stopping) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
            if (active.get() > 0) {
                continue;
            }
            long idle = System.currentTimeMillis() - lastUsed;
            if (idle >= idleMillis) {
                stop("idle for " + format(idle));
            } else if (!DaemonFiles.names(dir, pid, token)) {
                stop("the registry names another daemon or is gone");
            } else if (!DaemonSettings.installedFingerprint().equals(installation)) {
                stop("the jars of axl changed");
            } else if (collected != lastUsed && idle >= Math.min(10_000, idleMillis / 2)) {
                // Gives the memory of the last job back to the system while waiting.
                collected = lastUsed;
                System.gc();
            }
        }
    }

    private void log(String message) {
        log.println(Instant.now() + " " + message);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** What a job prints, in order, sent once the job is done. */
    private static final class Output {

        private final List<Byte> streams = new ArrayList<>();
        private final List<ByteArrayOutputStream> chunks = new ArrayList<>();

        PrintStream stream(byte kind) {
            return new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    chunk(kind).write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    chunk(kind).write(bytes, offset, length);
                }
            }, true, StandardCharsets.UTF_8);
        }

        private synchronized ByteArrayOutputStream chunk(byte kind) {
            if (streams.isEmpty() || streams.getLast() != kind) {
                streams.add(kind);
                chunks.add(new ByteArrayOutputStream());
            }
            return chunks.getLast();
        }

        synchronized void writeTo(DataOutputStream out) throws IOException {
            for (int i = 0; i < chunks.size(); i++) {
                out.writeByte(streams.get(i));
                DaemonProtocol.writeString(out, chunks.get(i).toString(StandardCharsets.UTF_8));
            }
        }
    }
}

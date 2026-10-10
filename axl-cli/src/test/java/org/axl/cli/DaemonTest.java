package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.axl.javac.JavaCompilation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @AfterEach
    void stopDaemons() {
        axl(settings("any"), "daemon", "--stop", "--all");
    }

    private DaemonSettings settings(String fingerprint) {
        return settings(fingerprint, Duration.ofMinutes(10));
    }

    private DaemonSettings settings(String fingerprint, Duration idle) {
        return new DaemonSettings(true, tmp.resolve("daemons"), idle, fingerprint);
    }

    private int axl(DaemonSettings settings, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return Main.commandLine(settings).execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private Path sources(String body) throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), "package app\n\nfn main() {\n" + body + "\n}\n");
        return src;
    }

    private static long pid(DaemonSettings settings) {
        DaemonFiles.Entry entry = DaemonFiles.read(settings.directory());
        assertNotNull(entry);
        return entry.pid();
    }

    @Test
    void theFirstCommandStartsTheDaemonAndLaterOnesReuseIt() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings settings = settings("one");
        assertEquals(0, axl(settings, "check", "--verbose", "--no-null-checks", src.toString()), err.toString());
        assertTrue(err.toString().contains("axl: started the compiler daemon"), err.toString());
        long pid = pid(settings);
        err.reset();
        assertEquals(0, axl(settings, "check", "--verbose", "--no-null-checks", src.toString()), err.toString());
        assertEquals("axl: compiling in the daemon (pid " + pid + ")\n", err.toString().replace("\r\n", "\n"));
        assertEquals(pid, pid(settings));

        out.reset();
        assertEquals(0, axl(settings, "daemon", "--status"));
        assertTrue(out.toString().contains("daemon, pid " + pid + ": up "), out.toString());
        assertTrue(out.toString().replace("\r\n", "\n").contains(", 2 jobs; stops after 10 min without work\n  installation: "
                + settings.installation() + " (this one)\n"), out.toString());

        out.reset();
        assertEquals(0, axl(settings, "daemon", "--stop"));
        assertEquals("Stopped the axl dev daemon of " + settings.installation() + " (pid " + pid + ")\n", out.toString().replace("\r\n", "\n"));
        assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).get().isAlive());
        assertNull(DaemonFiles.read(settings.directory()));
        out.reset();
        assertEquals(0, axl(settings, "daemon", "--status"));
        assertEquals("No axl daemon is running\n", out.toString().replace("\r\n", "\n"));
    }

    @Test
    void diagnosticsAndExitCodesAreThoseOfACompilationInProcess() throws Exception {
        Path src = sources("    val s: String? = null\n    println(s.length)");
        assertEquals(1, axl(settings("one").disabled(), "check", src.toString()));
        String expected = out.toString(StandardCharsets.UTF_8);
        assertTrue(expected.contains("Main.axl:5:"), expected);
        out.reset();
        assertEquals(1, axl(settings("one"), "check", src.toString()));
        assertEquals(expected, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anotherInstallationStartsItsOwnDaemon() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings one = settings("one");
        DaemonSettings two = settings("two");
        assertEquals(0, axl(one, "check", "--no-null-checks", src.toString()), err.toString());
        assertEquals(0, axl(two, "check", "--no-null-checks", src.toString()), err.toString());
        assertNotEquals(pid(one), pid(two));

        // A registry that names the daemon of another installation, as if it had been copied.
        DaemonSettings three = settings("three");
        Files.createDirectories(three.directory());
        Files.copy(one.directory().resolve(DaemonFiles.REGISTRY), three.directory().resolve(DaemonFiles.REGISTRY));
        assertEquals(0, axl(three, "check", "--no-null-checks", src.toString()), err.toString());
        assertNotEquals(pid(one), pid(three));
        assertNotEquals(pid(two), pid(three));

        out.reset();
        assertEquals(0, axl(one, "daemon", "--status"));
        assertEquals(6, out.toString().lines().count(), out.toString());
        String own = "  installation: " + one.installation() + " (this one)";
        assertEquals(3, out.toString().lines().filter(own::equals).count(), out.toString());
    }

    @Test
    void stopStopsTheDaemonsOfThisInstallationAndAllThoseOfEveryInstallation() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings here = settings("one");
        DaemonSettings there = new DaemonSettings(true, here.baseDir(), Duration.ofMinutes(10), "two", tmp.resolve("other-axl"));
        assertEquals(0, axl(here, "check", "--no-null-checks", src.toString()), err.toString());
        assertEquals(0, axl(there, "check", "--no-null-checks", src.toString()), err.toString());
        long herePid = pid(here);
        long therePid = pid(there);

        out.reset();
        assertEquals(0, axl(here, "daemon", "--status"));
        String status = out.toString().replace("\r\n", "\n");
        assertTrue(status.contains("\n  installation: " + here.installation() + " (this one)\n"), status);
        assertTrue(status.contains("\n  installation: " + there.installation() + "\n"), status);

        out.reset();
        assertEquals(0, axl(here, "daemon", "--stop"));
        assertEquals("Stopped the axl dev daemon of " + here.installation() + " (pid " + herePid + ")\n"
                + "1 daemon of other installations left running; axl daemon --stop --all stops them too\n",
                out.toString().replace("\r\n", "\n"));
        assertNull(DaemonFiles.read(here.directory()));
        assertEquals(therePid, pid(there));

        // The registry of an earlier build names no installation; the daemon's class path still does, where
        // the system tells the arguments of a process (not on Windows).
        DaemonFiles.Entry entry = DaemonFiles.read(there.directory());
        DaemonFiles.write(there.directory(), new DaemonFiles.Entry(entry.pid(), entry.port(), entry.token(), entry.fingerprint(),
                entry.version(), null));
        boolean windows = File.separatorChar == '\\';
        String installation = windows ? "unknown (an earlier build of axl)" : here.installation().toString();
        DaemonSettings elsewhere = new DaemonSettings(true, here.baseDir(), Duration.ofMinutes(10), "three", tmp.resolve("third-axl"));
        out.reset();
        assertEquals(0, axl(elsewhere, "daemon", "--status"));
        assertTrue(out.toString().replace("\r\n", "\n").endsWith("\n  installation: " + installation + "\n"), out.toString());
        out.reset();
        assertEquals(0, axl(elsewhere, "daemon", "--stop"));
        assertEquals("No axl daemon of this installation is running\n"
                + "1 daemon of other installations left running; axl daemon --stop --all stops them too\n",
                out.toString().replace("\r\n", "\n"));

        out.reset();
        assertEquals(0, axl(elsewhere, "daemon", "--stop", "--all"));
        assertEquals("Stopped the axl dev daemon" + (windows ? "" : " of " + installation) + " (pid " + therePid + ")\n",
                out.toString().replace("\r\n", "\n"));
        assertTrue(ProcessHandle.of(therePid).isEmpty() || !ProcessHandle.of(therePid).get().isAlive());
    }

    @Test
    void compilesInThisProcessWhenNoDaemonCanStart() throws Exception {
        Path src = sources("    println(1)");
        Path notADirectory = Files.writeString(tmp.resolve("file"), "");
        var settings = new DaemonSettings(true, notADirectory.resolve("daemons"), Duration.ofMinutes(10), "one");
        assertEquals(0, axl(settings, "check", "--verbose", "--no-null-checks", src.toString()), err.toString());
        assertTrue(err.toString().startsWith("axl: compiling in this process: the compiler daemon failed: "), err.toString());
        err.reset();
        assertEquals(0, axl(settings, "check", "--no-null-checks", src.toString()), err.toString());
        assertEquals("", err.toString());
    }

    @Test
    void compilesInThisProcessWithoutTheDaemon() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings settings = settings("one");
        assertEquals(0, axl(settings, "check", "--no-daemon", "--no-null-checks", src.toString()), err.toString());
        assertEquals(0, axl(settings.disabled(), "check", "--no-null-checks", src.toString()), err.toString());
        assertFalse(Files.exists(settings.directory()));
    }

    @Test
    void aDaemonThatDiedIsReplaced() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings settings = settings("one");
        assertEquals(0, axl(settings, "check", "--no-null-checks", src.toString()), err.toString());
        long pid = pid(settings);
        ProcessHandle daemon = ProcessHandle.of(pid).orElseThrow();
        daemon.destroyForcibly();
        daemon.onExit().get(30, TimeUnit.SECONDS);
        assertEquals(0, axl(settings, "check", "--no-null-checks", src.toString()), err.toString());
        assertNotEquals(pid, pid(settings));
    }

    @Test
    void twoCommandsAtOnceStartOneDaemon() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings settings = settings("one");
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Integer> codes = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    Path work = WorkDirectories.create(WorkDirectories.CHECK);
                    var job = new CompileJob(CompileJob.Kind.CHECK, List.of(src), null, work.resolve("generated"),
                            work.resolve("classes"), List.of(), false, false, false, false, true, false, Paths.workingDirectory());
                    codes.add(new DaemonClient(settings, false).compile(job).success() ? 0 : 1);
                    WorkDirectories.delete(work);
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(List.of(), failures);
        assertEquals(List.of(0, 0), codes);
        out.reset();
        assertEquals(0, axl(settings, "daemon", "--status"));
        assertTrue(out.toString().contains(", 2 jobs;"), out.toString());
    }

    @Test
    void theDaemonEndsAfterTheIdleTime() throws Exception {
        Path src = sources("    println(1)");
        DaemonSettings settings = settings("one", Duration.ofSeconds(1));
        assertEquals(0, axl(settings, "check", "--no-null-checks", src.toString()), err.toString());
        ProcessHandle daemon = ProcessHandle.of(pid(settings)).orElseThrow();
        daemon.onExit().get(30, TimeUnit.SECONDS);
        assertNull(DaemonFiles.read(settings.directory()));
    }

    @Test
    void idleTimes() {
        assertEquals(Duration.ofMinutes(45), DaemonSettings.idle("45"));
        assertEquals(Duration.ofMinutes(30), DaemonSettings.idle("30m"));
        assertEquals(Duration.ofHours(8), DaemonSettings.idle(" 8H "));
        assertEquals(Duration.ofSeconds(90), DaemonSettings.idle("90s"));
        assertEquals(DaemonSettings.DEFAULT_IDLE, DaemonSettings.idle(null));
        assertEquals(DaemonSettings.DEFAULT_IDLE, DaemonSettings.idle("0"));
        assertEquals(DaemonSettings.DEFAULT_IDLE, DaemonSettings.idle("soon"));
    }

    /**
     * axl as a process, as from a terminal: the program gets the launcher's stdin and its exit code,
     * and the launcher's output ends with the launcher, not with the daemon it started.
     */
    @Test
    void runKeepsTheProgramsStdinOutputAndExitCode() throws Exception {
        Path src = sources("    val line = readLine()\n    println(\"got \" + (line ?: \"nothing\"))\n    System.exit(3)");
        Path home = Files.createDirectories(tmp.resolve("home"));
        for (int round = 0; round < 2; round++) {
            Process process = axlProcess(home, "run", "--no-null-checks", src.resolve("app/Main.axl").toString());
            process.getOutputStream().write("hello\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertEquals("got hello\n", printed.replace("\r\n", "\n"));
            assertEquals(3, process.exitValue());
        }
        var settings = new DaemonSettings(true, home.resolve(".axl/daemon"), Duration.ofMinutes(1), "any");
        List<Path> daemons = DaemonFiles.directories(settings.baseDir());
        // Windows uses no daemon when the output goes to a pipe (see DaemonSettings).
        assertEquals(File.separatorChar == '/' ? 1 : 0, daemons.size(), daemons.toString());
        daemons.forEach(dir -> assertNotNull(DaemonFiles.read(dir)));
        axl(settings, "daemon", "--stop");
    }

    private static Process axlProcess(Path home, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(JavaCompilation.ERROR_PRONE_JVM_ARGS);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", home.toString());
        builder.environment().remove("AXL_DAEMON");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        return builder.start();
    }
}

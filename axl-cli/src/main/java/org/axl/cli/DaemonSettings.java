package org.axl.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether commands compile in a daemon, where its files are, and how long it waits for work.
 * AXL_DAEMON=off switches the daemon off, AXL_DAEMON_IDLE sets the time, such as 30m or 8h.
 *
 * @param fingerprint the installation the daemon belongs to, with its Java and settings
 * @param installation the directory axl is installed in, which `axl daemon` shows and stops the daemons of
 */
record DaemonSettings(boolean enabled, Path baseDir, Duration idle, String fingerprint, Path installation) {

    static final Duration DEFAULT_IDLE = Duration.ofHours(3);
    private static final Pattern DURATION = Pattern.compile("(\\d{1,9})([smh]?)");

    DaemonSettings(boolean enabled, Path baseDir, Duration idle, String fingerprint) {
        this(enabled, baseDir, idle, fingerprint, installationOf(System.getProperty("java.class.path", "")));
    }

    static DaemonSettings fromEnvironment() {
        String switched = System.getenv("AXL_DAEMON");
        boolean enabled = switched == null
                || !List.of("off", "false", "no", "0").contains(switched.strip().toLowerCase(Locale.ROOT));
        return new DaemonSettings(enabled && passesNoPipes(), LocalRepository.userHome().resolve(".axl").resolve("daemon"),
                idle(System.getenv("AXL_DAEMON_IDLE")), Installation.FINGERPRINT);
    }

    /**
     * On Windows a new process inherits every inheritable handle of the process that starts it: a daemon
     * started by an axl whose output goes to a pipe would keep the pipe open, and whoever reads it, such
     * as an editor, would wait for the daemon to end. axl compiles in process there unless it runs in a console.
     */
    private static boolean passesNoPipes() {
        return File.separatorChar != '\\' || System.console() != null;
    }

    DaemonSettings disabled() {
        return new DaemonSettings(false, baseDir, idle, fingerprint, installation);
    }

    /**
     * The directory of an installation, from the class path it starts axl with: the parent of the lib
     * directory of its first jar (lib/app in the Linux packages, app on Windows), or the class directory
     * of a development build.
     */
    static Path installationOf(String classPath) {
        String first = classPath.split(Pattern.quote(File.pathSeparator), -1)[0];
        Path path = Path.of(first).toAbsolutePath().normalize();
        if (!first.endsWith(".jar") || path.getParent() == null) {
            return path;
        }
        Path dir = path.getParent();
        for (String name : List.of("app", "lib")) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals(name) && dir.getParent() != null) {
                dir = dir.getParent();
            }
        }
        return dir;
    }

    /** A number of minutes, or a number with s, m or h; anything else gives the default. */
    static Duration idle(@Nullable String text) {
        Matcher matcher = DURATION.matcher(text == null ? "" : text.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches() || Long.parseLong(matcher.group(1)) == 0) {
            return DEFAULT_IDLE;
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofMinutes(amount);
        };
    }

    /** The directory of the daemon of this installation. */
    Path directory() {
        return baseDir.resolve(version() + "-" + fingerprint);
    }

    /** Computed again each time, for a daemon to see that its jars were replaced. */
    static String installedFingerprint() {
        return Installation.compute();
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    /**
     * Launchers share a daemon only when it runs the same jars on the same Java, and with the same
     * settings of the JVM that change what a build writes or where its temporary files are.
     */
    private static final class Installation {

        static final String FINGERPRINT = compute();

        private static String compute() {
            StringBuilder text = new StringBuilder();
            for (String property : List.of("java.home", "java.vm.version", "file.encoding", "sun.jnu.encoding", "java.io.tmpdir")) {
                text.append(property).append('=').append(System.getProperty(property, "")).append('\n');
            }
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                text.append(entry);
                try {
                    BasicFileAttributes attributes = Files.readAttributes(Path.of(entry), BasicFileAttributes.class);
                    text.append(' ').append(attributes.size()).append(' ').append(attributes.lastModifiedTime().toMillis());
                } catch (IOException | RuntimeException e) {
                    text.append(" missing");
                }
                text.append('\n');
            }
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash, 0, 6);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

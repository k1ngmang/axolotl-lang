package org.axl.cli;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The files of a user's compiler daemons: a directory per installation of axl, such as
 * ~/.axl/daemon/0.2.0-1a2b3c4d5e6f, with the registry of its running daemon (port and token, readable
 * by the owner only), the daemon's log, and the lock that lets one launcher at a time start a daemon.
 */
final class DaemonFiles {

    static final String REGISTRY = "registry.properties";
    static final String LOG = "daemon.log";
    static final String START_LOCK = "start.lock";

    private DaemonFiles() {}

    /** The installation is missing in the entries of daemons of earlier builds of axl. */
    record Entry(long pid, int port, String token, String fingerprint, String version, @Nullable String installation) {}

    /** Creates the directories with access for the owner only, where the file system has POSIX permissions. */
    static void createPrivateDirectories(Path dir) throws IOException {
        if (posix()) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(dir);
        }
    }

    static @Nullable Entry read(Path dir) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(dir.resolve(REGISTRY), StandardCharsets.UTF_8)) {
            properties.load(reader);
            return new Entry(Long.parseLong(properties.getProperty("pid")), Integer.parseInt(properties.getProperty("port")),
                    properties.getProperty("token"), properties.getProperty("fingerprint"), properties.getProperty("version"),
                    properties.getProperty("installation"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Written under another name and then moved, so that a launcher never reads half an entry. */
    static void write(Path dir, Entry entry) throws IOException {
        Path temporary = dir.resolve(REGISTRY + "." + entry.pid());
        Files.deleteIfExists(temporary);
        if (posix()) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(temporary);
        }
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(entry.pid()));
        properties.setProperty("port", Integer.toString(entry.port()));
        properties.setProperty("token", entry.token());
        properties.setProperty("fingerprint", entry.fingerprint());
        properties.setProperty("version", entry.version());
        if (entry.installation() != null) {
            properties.setProperty("installation", entry.installation());
        }
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        Files.move(temporary, dir.resolve(REGISTRY), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Whether the registry still names this daemon: a launcher may have replaced it, or the user deleted it. */
    static boolean names(Path dir, long pid, String token) {
        Entry entry = read(dir);
        return entry != null && entry.pid() == pid && entry.token().equals(token);
    }

    static void deleteIfOwn(Path dir, long pid, String token) {
        if (names(dir, pid, token)) {
            try {
                Files.deleteIfExists(dir.resolve(REGISTRY));
            } catch (IOException e) {
                // A launcher that finds no daemon at the port starts another one.
            }
        }
    }

    /** The directories of the daemons of every installation. */
    static List<Path> directories(Path base) {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, Files::isDirectory)) {
            stream.forEach(dirs::add);
        } catch (IOException e) {
            // No daemon has run yet.
        }
        dirs.sort(null);
        return dirs;
    }

    /**
     * Deletes the directories of other installations whose daemon has ended, such as those of earlier
     * versions. A directory whose start lock is held belongs to a daemon being started.
     */
    static void removeEnded(Path base, Path keep) {
        for (Path dir : directories(base)) {
            Entry entry = read(dir);
            boolean running = entry != null && ProcessHandle.of(entry.pid()).map(ProcessHandle::isAlive).orElse(false);
            if (dir.equals(keep) || running) {
                continue;
            }
            try (FileChannel channel = FileChannel.open(dir.resolve(START_LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    Paths.deleteRecursively(dir);
                }
            } catch (IOException | OverlappingFileLockException e) {
                // Left for a later launcher.
            }
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}

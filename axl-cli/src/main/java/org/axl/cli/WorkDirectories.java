package org.axl.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Temporary directories of `axl run` and `axl check`. Each stays locked while its JVM runs, and a later
 * command deletes the directories whose lock is free, such as those of a killed `axl check`. `axl run`
 * never deletes its own directory: the program may load classes from it until the JVM ends, in its own
 * shutdown hooks too.
 */
final class WorkDirectories {

    static final String RUN = "axl-run";
    static final String CHECK = "axl-check";
    private static final String LOCK = ".lock";
    // Referenced so that the locks are held until the JVM ends or the directory is deleted.
    private static final Map<Path, FileChannel> HELD = new HashMap<>();

    private WorkDirectories() {}

    /** A directory made by {@link #create}: its name ends in a number, and it has a lock file. */
    static boolean isWorkDirectory(Path dir) {
        return (isNamed(dir, RUN) || isNamed(dir, CHECK)) && Files.exists(dir.resolve(LOCK));
    }

    private static boolean isNamed(Path dir, String prefix) {
        String name = dir.getFileName().toString();
        return name.startsWith(prefix) && name.length() > prefix.length()
                && name.substring(prefix.length()).chars().allMatch(Character::isDigit);
    }

    static synchronized Path create(String prefix) throws IOException {
        deleteFinished(prefix);
        Path dir = Files.createTempDirectory(Paths.TEMP, prefix);
        Path unlocked = dir.resolve(LOCK + ".new");
        FileChannel lock = FileChannel.open(unlocked, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        lock.lock();
        // Other JVMs delete a directory whose `.lock` is free, so the file gets that name only once it is locked.
        Files.move(unlocked, dir.resolve(LOCK), StandardCopyOption.ATOMIC_MOVE);
        HELD.put(dir, lock);
        return dir;
    }

    /** Whether this JVM made the directory and holds its lock. */
    static synchronized boolean holds(Path dir) {
        return HELD.containsKey(dir);
    }

    /** Releases the lock of a directory made by this JVM and deletes the directory. */
    static synchronized void delete(Path dir) throws IOException {
        HELD.remove(dir).close();
        Paths.deleteRecursively(dir);
    }

    private static void deleteFinished(String prefix) {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(Paths.TEMP, dir -> isNamed(dir, prefix))) {
            for (Path dir : dirs) {
                if (!HELD.containsKey(dir) && isFinished(dir)) {
                    Paths.deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            // Leftovers in the temporary directory do no harm.
        }
    }

    /**
     * Never called for a directory of this JVM: on POSIX, closing any channel to a file releases all of
     * the process's locks on it. A directory whose lock file cannot be opened is just being created or
     * belongs to another user.
     */
    private static boolean isFinished(Path dir) {
        try (FileChannel channel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            return lock != null;
        } catch (IOException | OverlappingFileLockException e) {
            return false;
        }
    }
}

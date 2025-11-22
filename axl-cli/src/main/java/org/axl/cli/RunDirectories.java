package org.axl.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Temporary directories of `axl run`. The program may load classes from its directory until the JVM
 * ends, in its own shutdown hooks too, so the directory is not deleted on exit: it stays locked while
 * the JVM runs, and a later `axl run` deletes the directories whose lock is free.
 */
final class RunDirectories {

    static final String PREFIX = "axl-run";
    private static final String LOCK = ".lock";
    private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));
    // Referenced so that the locks are held until the JVM ends.
    private static final List<FileChannel> HELD = new ArrayList<>();

    private RunDirectories() {}

    static synchronized Path create() throws IOException {
        deleteFinished();
        Path dir = Files.createTempDirectory(PREFIX);
        FileChannel lock = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock.lock();
        HELD.add(lock);
        return dir;
    }

    private static void deleteFinished() {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(TEMP, PREFIX + "*")) {
            for (Path dir : dirs) {
                if (isFinished(dir)) {
                    Paths.deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            // Leftovers in the temporary directory do no harm.
        }
    }

    /**
     * A lock held by this JVM throws OverlappingFileLockException. A directory whose lock file cannot
     * be opened is just being created or belongs to another user.
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

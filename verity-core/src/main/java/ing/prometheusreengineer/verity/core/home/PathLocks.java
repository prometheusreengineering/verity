package ing.prometheusreengineer.verity.core.home;

import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Named locks that hold both within this JVM and across processes (UI and CLI sharing one home), used to
 * serialize installs of the same version.
 */
public final class PathLocks {
    private final Path dir;
    private final ConcurrentHashMap<String, ReentrantLock> local = new ConcurrentHashMap<>();

    public PathLocks(Path dir) {
        this.dir = dir;
    }

    public interface Body<T> {
        T run() throws Exception;
    }

    public <T> T withLock(String name, Body<T> body) throws Exception {
        ReentrantLock lock = local.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(FileUtil.safeName(name) + ".lock");
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fl = acquire(ch);
                try {
                    return body.run();
                } finally {
                    if (fl != null) fl.release();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static FileLock acquire(FileChannel ch) throws IOException {
        try {
            return ch.lock();
        } catch (OverlappingFileLockException e) {
            return null; // already held by this JVM through another channel; the in-process lock covers us
        }
    }
}

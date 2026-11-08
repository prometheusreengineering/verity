package ing.prometheusreengineer.verity.core.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

public final class FileUtil {
    private FileUtil() {
    }

    /** Writes via a sibling temp file and an atomic rename so readers never see partial content. */
    public static void writeAtomically(Path file, byte[] data) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = parent.resolve(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, data);
            moveAtomically(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void moveAtomically(Path from, Path to) throws IOException {
        Files.createDirectories(to.toAbsolutePath().getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                file.toFile().setWritable(true);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void copyRecursively(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.createDirectories(to.toAbsolutePath().getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Makes a string safe to use as a single path segment on every OS. */
    public static String safeName(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9._+-]", "_");
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    public static long sizeOf(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }
}

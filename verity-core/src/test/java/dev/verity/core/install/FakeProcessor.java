package dev.verity.core.install;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stand-in for a Forge installer processor: writes its arguments to the --out file. With {@code --exclusive <file>}
 * it fails if another processor holding the same file is running, and {@code --sleep <ms>} widens that window.
 */
public class FakeProcessor {
    public static void main(String[] args) throws Exception {
        String out = null;
        Path exclusive = null;
        long sleep = 0;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = args[++i];
                case "--exclusive" -> exclusive = Path.of(args[++i]);
                case "--sleep" -> sleep = Long.parseLong(args[++i]);
                default -> b.append(args[i]).append('\n');
            }
        }
        if (exclusive != null) {
            Files.createDirectories(exclusive.getParent());
            try {
                Files.createFile(exclusive);
            } catch (FileAlreadyExistsException e) {
                System.err.println("another processor is writing the same outputs");
                System.exit(1);
            }
        }
        try {
            Thread.sleep(sleep);
            Path p = Path.of(out);
            Files.createDirectories(p.getParent());
            Files.writeString(p, b.toString());
        } finally {
            if (exclusive != null) Files.delete(exclusive);
        }
    }
}

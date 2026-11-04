package dev.verity.core.install;

import java.nio.file.Files;
import java.nio.file.Path;

/** Stand-in for a Forge installer processor: writes its arguments to the --out file. */
public class FakeProcessor {
    public static void main(String[] args) throws Exception {
        String out = null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out")) out = args[++i];
            else b.append(args[i]).append('\n');
        }
        Path p = Path.of(out);
        Files.createDirectories(p.getParent());
        Files.writeString(p, b.toString());
    }
}

package dev.verity.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/** Builds small jars for tests. */
public final class TestJars {
    private TestJars() {
    }

    public static Path jar(Path file, Map<String, String> textEntries, Map<String, String> manifest) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        try (OutputStream os = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(os, mf)) {
            for (Map.Entry<String, String> e : textEntries.entrySet()) {
                jar.putNextEntry(new ZipEntry(e.getKey()));
                jar.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }

    public static Path jar(Path file, Map<String, String> textEntries) throws IOException {
        return jar(file, textEntries, Map.of());
    }
}

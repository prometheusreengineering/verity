package dev.verity.core.launch;

import dev.verity.core.install.Libraries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts native libraries for one instance. */
public final class Natives {
    private Natives() {
    }

    public static void extract(List<Libraries.Resolved> libraries, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        for (Libraries.Resolved r : libraries) {
            boolean lwjgl3Natives = !r.natives() && r.coordinate().classifier() != null
                    && r.coordinate().classifier().startsWith("natives-");
            if (!r.natives() && !lwjgl3Natives) continue;
            if (!Files.isRegularFile(r.path())) continue;
            List<String> excludes = r.extractExcludes().isEmpty() ? List.of("META-INF/") : r.extractExcludes();
            try (ZipFile zip = new ZipFile(r.path().toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory() || excludes.stream().anyMatch(e.getName()::startsWith)) continue;
                    String name = e.getName();
                    if (lwjgl3Natives) {
                        // LWJGL 3 nests natives under <os>/<arch>/org/lwjgl/...; flatten like the official launcher.
                        if (!(name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".jnilib"))) continue;
                        name = name.substring(name.lastIndexOf('/') + 1);
                    }
                    Path target = nativesDir.resolve(name).normalize();
                    if (!target.startsWith(nativesDir)) continue;
                    Files.createDirectories(target.getParent());
                    try (var in = zip.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }
}

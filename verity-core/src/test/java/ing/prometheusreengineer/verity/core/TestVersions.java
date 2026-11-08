package ing.prometheusreengineer.verity.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies fixture version JSONs into a temporary versions/ directory. */
public final class TestVersions {
    private TestVersions() {
    }

    public static Path install(Path versionsDir, String... ids) throws IOException {
        for (String id : ids) {
            Path dst = versionsDir.resolve(id).resolve(id + ".json");
            Files.createDirectories(dst.getParent());
            try (InputStream in = TestVersions.class.getResourceAsStream("/versions/" + id + ".json")) {
                if (in == null) throw new IOException("missing fixture " + id);
                Files.copy(in, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return versionsDir;
    }
}

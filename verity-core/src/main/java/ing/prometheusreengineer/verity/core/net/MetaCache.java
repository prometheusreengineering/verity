package ing.prometheusreengineer.verity.core.net;

import ing.prometheusreengineer.verity.core.util.FileUtil;
import ing.prometheusreengineer.verity.core.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Caches small metadata documents (version lists, manifests) on disk. Fresh entries are served without a
 * request; stale ones are refreshed but still used if the network is unavailable, so Verity keeps working
 * offline once everything is cached.
 */
public final class MetaCache {
    private final Http http;
    private final Path dir;
    private final Duration ttl;

    public MetaCache(Http http, Path dir, Duration ttl) {
        this.http = http;
        this.dir = dir;
        this.ttl = ttl;
    }

    public Http http() {
        return http;
    }

    public String get(String url) throws IOException {
        return get(url, ttl);
    }

    public String get(String url, Duration maxAge) throws IOException {
        Path file = dir.resolve(Hashing.sha1(url.getBytes(StandardCharsets.UTF_8)) + ".cache");
        if (Files.isRegularFile(file)) {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (modified.plus(maxAge).isAfter(Instant.now())) return Files.readString(file);
        }
        try {
            String body = http.getString(URI.create(url));
            FileUtil.writeAtomically(file, body.getBytes(StandardCharsets.UTF_8));
            return body;
        } catch (IOException e) {
            if (Files.isRegularFile(file)) return Files.readString(file);
            throw e;
        }
    }

    /** For immutable documents addressed by hash or exact version: cache forever. */
    public String getImmutable(String url) throws IOException {
        return get(url, Duration.ofDays(36500));
    }
}

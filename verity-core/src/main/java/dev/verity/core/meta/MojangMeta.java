package dev.verity.core.meta;

import dev.verity.core.home.VerityHome;
import dev.verity.core.net.Downloader;
import dev.verity.core.net.MetaCache;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mojang's version manifest and per-version JSON files. */
public final class MojangMeta {
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final VerityHome home;
    private final MetaCache cache;
    private final Downloader downloader;
    private volatile VersionManifest manifest;

    public MojangMeta(VerityHome home, MetaCache cache, Downloader downloader) {
        this.home = home;
        this.cache = cache;
        this.downloader = downloader;
    }

    public VersionManifest manifest() throws IOException {
        VersionManifest m = manifest;
        if (m == null) {
            m = Json.MAPPER.readValue(cache.get(MANIFEST_URL), VersionManifest.class);
            manifest = m;
        }
        return m;
    }

    public Path versionJsonPath(String id) {
        return home.versions().resolve(id).resolve(id + ".json");
    }

    /** Downloads (if needed) and parses the vanilla version JSON for {@code id}. */
    public VersionJson vanillaVersion(String id) throws IOException {
        Path file = versionJsonPath(id);
        if (!Files.isRegularFile(file)) {
            VersionManifest.Entry e = manifest().find(id)
                    .orElseThrow(() -> new IOException("Unknown Minecraft version '" + id + "'"));
            downloader.download(Downloader.Artifact.of(e.url, file, e.sha1, -1));
        }
        return Json.read(file, VersionJson.class);
    }
}

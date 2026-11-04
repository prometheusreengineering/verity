package dev.verity.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import dev.verity.core.meta.Rule;
import dev.verity.core.meta.VersionJson;
import dev.verity.core.net.Downloader;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Json;
import dev.verity.core.util.Progress;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Makes an already-described version launchable: vanilla JSON, client jar, libraries, assets and logging
 * config. Loader installers write their version JSON first and then call {@link #complete}.
 */
public final class GameInstaller {
    public static final String RESOURCES = "https://resources.download.minecraft.net/";

    private final InstallContext ctx;

    public GameInstaller(InstallContext ctx) {
        this.ctx = ctx;
    }

    /** Downloads vanilla {@code mc}'s JSON and client jar. */
    public void ensureVanilla(String mc, Progress progress) throws IOException {
        progress.stage("Fetching Minecraft " + mc);
        VersionJson v = ctx.meta().mojang().vanillaVersion(mc);
        VersionJson.DownloadInfo client = v.downloads == null ? null : v.downloads.get("client");
        if (client == null) throw new IOException("Minecraft " + mc + " has no client download");
        ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(client.url)), clientJar(mc), client.sha1, client.size, true));
    }

    public Path clientJar(String id) {
        return ctx.home().versions().resolve(id).resolve(id + ".jar");
    }

    /** Installs everything {@code versionId} needs and returns its flattened JSON. */
    public InstalledVersion complete(String versionId, Progress progress) throws IOException {
        List<VersionJson> chain = ctx.resolver().chain(versionId);
        VersionJson merged = VersionResolver.merge(chain);
        String baseJar = VersionResolver.baseJarId(chain);
        Rule.Environment env = Rule.Environment.current();

        progress.stage("Downloading libraries for " + versionId);
        List<Downloader.Artifact> libs = new ArrayList<>();
        for (Libraries.Resolved r : Libraries.resolve(merged, env, ctx.home().libraries())) {
            if (r.localOnly()) {
                if (!Files.isRegularFile(r.path())) {
                    throw new IOException("Library " + r.coordinate() + " should have been produced by the installer but is missing");
                }
                continue;
            }
            libs.add(r.artifact());
        }
        ctx.downloader().downloadAll(libs, progress);

        // The official launcher runs a child version from its own copy of the base jar (Forge's ignoreList relies on
        // the ${version_name}.jar filename).
        Path jar = clientJar(versionId);
        if (!versionId.equals(baseJar)) {
            Path base = clientJar(baseJar);
            if (!Files.isRegularFile(base)) throw new IOException("Base client jar " + base + " missing");
            // Compare bytes, not sizes: a copy torn by a crash or power loss keeps its size but not its content.
            if (!Files.isRegularFile(jar) || Files.mismatch(jar, base) != -1) {
                Files.createDirectories(jar.getParent());
                Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
                Files.copy(base, tmp, StandardCopyOption.REPLACE_EXISTING);
                FileUtil.moveAtomically(tmp, jar);
            }
        }

        installAssets(merged, progress);
        installLogging(merged);
        return new InstalledVersion(versionId, merged, jar);
    }

    void installAssets(VersionJson v, Progress progress) throws IOException {
        if (v.assetIndex == null) return;
        progress.stage("Downloading assets (" + v.assetIndex.id + ")");
        Path assets = ctx.home().assets();
        Path indexFile = assets.resolve("indexes").resolve(v.assetIndex.id + ".json");
        ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(v.assetIndex.url)), indexFile, v.assetIndex.sha1,
                v.assetIndex.size, true));
        JsonNode index = Json.tree(indexFile);
        boolean virtual = index.path("virtual").asBoolean(false) || index.path("map_to_resources").asBoolean(false);
        List<Downloader.Artifact> objects = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = index.path("objects").properties().iterator();
        List<Map.Entry<String, String>> virtualCopies = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String hash = e.getValue().path("hash").asText();
            long size = e.getValue().path("size").asLong(-1);
            String rel = hash.substring(0, 2) + "/" + hash;
            objects.add(new Downloader.Artifact(List.of(URI.create(RESOURCES + rel)), assets.resolve("objects").resolve(rel), hash,
                    size, false));
            if (virtual) virtualCopies.add(Map.entry(e.getKey(), rel));
        }
        ctx.downloader().downloadAll(objects, progress);
        if (virtual) {
            Path vdir = virtualAssetsDir(v.assetIndex.id);
            for (Map.Entry<String, String> c : virtualCopies) {
                Path dst = vdir.resolve(c.getKey());
                if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(assets.resolve("objects").resolve(c.getValue()), dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public Path virtualAssetsDir(String indexId) {
        return ctx.home().assets().resolve("virtual").resolve(indexId);
    }

    public boolean isVirtualAssets(VersionJson v) {
        if (v.assetIndex == null) return false;
        Path indexFile = ctx.home().assets().resolve("indexes").resolve(v.assetIndex.id + ".json");
        try {
            JsonNode index = Json.tree(indexFile);
            return index.path("virtual").asBoolean(false) || index.path("map_to_resources").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isMapToResources(VersionJson v) {
        if (v.assetIndex == null) return false;
        try {
            return Json.tree(ctx.home().assets().resolve("indexes").resolve(v.assetIndex.id + ".json"))
                    .path("map_to_resources").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    void installLogging(VersionJson v) throws IOException {
        Path cfg = loggingConfig(v);
        if (cfg == null) return;
        VersionJson.DownloadInfo f = v.logging.client.file;
        ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(f.url)), cfg, f.sha1, f.size, true));
    }

    public Path loggingConfig(VersionJson v) {
        if (v.logging == null || v.logging.client == null || v.logging.client.file == null || v.logging.client.file.url == null) {
            return null;
        }
        String id = v.logging.client.file instanceof VersionJson.FileRef ref && ref.id != null ? ref.id
                : URI.create(v.logging.client.file.url).getPath().replaceAll(".*/", "");
        return ctx.home().assets().resolve("log_configs").resolve(id);
    }
}

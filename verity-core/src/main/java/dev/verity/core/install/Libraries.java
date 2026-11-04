package dev.verity.core.install;

import dev.verity.core.meta.Library;
import dev.verity.core.meta.Rule;
import dev.verity.core.meta.VersionJson;
import dev.verity.core.net.Downloader;
import dev.verity.core.util.MavenCoordinate;
import dev.verity.core.util.Os;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Turns version JSON library entries into concrete files, URLs and classpath/natives roles. */
public final class Libraries {
    public static final String MOJANG = "https://libraries.minecraft.net/";
    public static final List<String> MIRRORS = List.of(
            MOJANG,
            "https://maven.minecraftforge.net/",
            "https://maven.neoforged.net/releases/",
            "https://maven.fabricmc.net/",
            "https://repo1.maven.org/maven2/");

    private Libraries() {
    }

    /**
     * @param natives true when this entry is a natives archive to extract (legacy {@code natives} map), false
     *                when it belongs on the classpath
     */
    public record Resolved(Library library, MavenCoordinate coordinate, Path path, List<URI> urls, String sha1, long size,
                           boolean natives, List<String> extractExcludes) {
        public Downloader.Artifact artifact() {
            return new Downloader.Artifact(urls, path, sha1, size, true);
        }

        /** Generated locally (e.g. by Forge processors) rather than downloaded. */
        public boolean localOnly() {
            return urls.isEmpty();
        }
    }

    public static List<Resolved> resolve(VersionJson version, Rule.Environment env, Path libraryDir) {
        List<Resolved> out = new ArrayList<>();
        for (Library lib : version.libraries) {
            if (!Rule.allowed(lib.rules, env)) continue;
            if (Boolean.FALSE.equals(lib.clientreq)) continue;
            MavenCoordinate coord;
            try {
                coord = lib.coordinate();
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (lib.hasNatives()) {
                String classifier = lib.natives.get(env.os().mojangName());
                if (classifier != null) {
                    classifier = classifier.replace("${arch}", Os.is64Bit() ? "64" : "32");
                    VersionJson.DownloadInfo info = lib.downloads != null && lib.downloads.classifiers != null
                            ? lib.downloads.classifiers.get(classifier) : null;
                    List<String> excludes = lib.extract != null && lib.extract.exclude != null ? lib.extract.exclude : List.of();
                    out.add(entry(lib, coord.withClassifier(classifier), info, libraryDir, true, excludes));
                }
                if (lib.downloads != null && lib.downloads.artifact != null) {
                    out.add(entry(lib, coord, lib.downloads.artifact, libraryDir, false, List.of()));
                }
                continue;
            }
            VersionJson.DownloadInfo info = lib.downloads != null ? lib.downloads.artifact : null;
            out.add(entry(lib, coord, info, libraryDir, false, List.of()));
        }
        return out;
    }

    private static Resolved entry(Library lib, MavenCoordinate coord, VersionJson.DownloadInfo info, Path libraryDir,
                                  boolean natives, List<String> excludes) {
        String rel = info != null && info.path != null && !info.path.isBlank() ? info.path : coord.path();
        Path path = libraryDir.resolve(rel);
        String sha1 = info != null && info.sha1 != null ? info.sha1 : lib.sha1;
        long size = info != null && info.size >= 0 ? info.size : (lib.size != null ? lib.size : -1);
        Set<String> urls = new LinkedHashSet<>();
        if (info != null && info.url != null) {
            if (!info.url.isBlank()) urls.add(normalizeUrl(info.url));
            // An explicit empty URL means "produced by the installer"; never try mirrors for it.
        } else {
            String base = lib.url != null && !lib.url.isBlank() ? normalizeUrl(lib.url) : MOJANG;
            if (!base.endsWith("/")) base += "/";
            urls.add(base + rel);
            for (String m : MIRRORS) urls.add(m + rel);
        }
        List<URI> uris = urls.stream().map(URI::create).toList();
        return new Resolved(lib, coord, path, uris, sha1, size, natives, excludes);
    }

    static String normalizeUrl(String url) {
        String u = url.trim()
                .replace("http://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/")
                .replace("https://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/");
        if (u.startsWith("http://")) u = "https://" + u.substring("http://".length());
        return u;
    }
}

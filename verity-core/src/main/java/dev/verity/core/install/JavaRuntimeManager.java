package dev.verity.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import dev.verity.core.net.Downloader;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Json;
import dev.verity.core.util.Os;
import dev.verity.core.util.Progress;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Provides the Java runtime each Minecraft version wants: Mojang's own runtimes (the same builds the official
 * launcher uses), falling back to Eclipse Temurin where Mojang has none (e.g. Linux on ARM), or a user override.
 */
public final class JavaRuntimeManager {
    public static final String MOJANG_RUNTIMES =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final InstallContext ctx;

    JavaRuntimeManager(InstallContext ctx) {
        this.ctx = ctx;
    }

    /** Returns the {@code java} executable to run {@code version} with, installing it if needed. */
    public Path javaFor(InstalledVersion version, Progress progress) throws Exception {
        return javaFor(version.javaComponent(), version.javaMajor(), progress);
    }

    public Path javaFor(String component, int major, Progress progress) throws Exception {
        String override = ctx.settings().javaOverrides.get(major);
        if (override != null && !override.isBlank()) return executableFromOverride(Path.of(override));
        return ctx.locks().withLock("runtime-" + component, () -> {
            String platform = mojangPlatform();
            if (platform != null) {
                Path exe = installMojang(component, platform, progress);
                if (exe != null) return exe;
            }
            return installTemurin(major, progress);
        });
    }

    static Path executableFromOverride(Path p) throws IOException {
        if (Files.isRegularFile(p)) return p;
        Path exe = p.resolve("bin").resolve(Os.current().javaExecutable(true));
        if (Files.isRegularFile(exe)) return exe;
        throw new IOException("Java override " + p + " is neither a java executable nor a Java home");
    }

    static String mojangPlatform() {
        String arch = Os.arch();
        return switch (Os.current()) {
            case WINDOWS -> switch (arch) {
                case "x86_64" -> "windows-x64";
                case "x86" -> "windows-x86";
                case "arm64" -> "windows-arm64";
                default -> null;
            };
            case LINUX -> switch (arch) {
                case "x86_64" -> "linux";
                case "x86" -> "linux-i386";
                default -> null;
            };
            case OSX -> arch.equals("arm64") ? "mac-os-arm64" : "mac-os";
        };
    }

    private Path runtimeDir(String component, String platform) {
        return ctx.home().runtimes().resolve(component).resolve(platform);
    }

    static Path executableIn(Path root) {
        Path mac = root.resolve("jre.bundle/Contents/Home/bin/java");
        if (Files.isRegularFile(mac)) return mac;
        return root.resolve("bin").resolve(Os.current().javaExecutable(true));
    }

    private Path installMojang(String component, String platform, Progress progress) throws IOException {
        Path dir = runtimeDir(component, platform);
        Path marker = dir.resolve(".verity-complete");
        if (Files.isRegularFile(marker)) return executableIn(dir);

        JsonNode all = Json.tree(ctx.cache().get(MOJANG_RUNTIMES));
        JsonNode entries = all.path(platform).path(component);
        if (!entries.isArray() || entries.isEmpty()) return null;
        JsonNode manifestRef = entries.get(0).path("manifest");
        progress.stage("Downloading Java runtime " + component + " (" + entries.get(0).path("version").path("name").asText() + ")");
        JsonNode manifest = Json.tree(ctx.cache().getImmutable(manifestRef.path("url").asText()));

        List<Downloader.Artifact> files = new ArrayList<>();
        List<Map.Entry<Path, String>> links = new ArrayList<>();
        List<Path> executables = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = manifest.path("files").properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Path target = dir.resolve(e.getKey());
            if (!target.normalize().startsWith(dir)) throw new IOException("Refusing runtime path " + e.getKey());
            JsonNode f = e.getValue();
            switch (f.path("type").asText()) {
                case "directory" -> Files.createDirectories(target);
                case "link" -> links.add(Map.entry(target, f.path("target").asText()));
                case "file" -> {
                    JsonNode raw = f.path("downloads").path("raw");
                    files.add(new Downloader.Artifact(List.of(URI.create(raw.path("url").asText())), target,
                            raw.path("sha1").asText(null), raw.path("size").asLong(-1), true));
                    if (f.path("executable").asBoolean(false)) executables.add(target);
                }
                default -> {
                }
            }
        }
        ctx.downloader().downloadAll(files, progress);
        for (Path exe : executables) exe.toFile().setExecutable(true, false);
        for (Map.Entry<Path, String> link : links) {
            if (Files.exists(link.getKey(), LinkOption.NOFOLLOW_LINKS)) continue;
            Files.createDirectories(link.getKey().getParent());
            try {
                Files.createSymbolicLink(link.getKey(), Path.of(link.getValue()));
            } catch (IOException | UnsupportedOperationException e) {
                Path resolved = link.getKey().getParent().resolve(link.getValue()).normalize();
                if (Files.isRegularFile(resolved)) Files.copy(resolved, link.getKey(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(marker, entries.get(0).path("version").path("name").asText());
        return executableIn(dir);
    }

    private Path installTemurin(int major, Progress progress) throws IOException {
        String os = switch (Os.current()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case OSX -> "mac";
        };
        String arch = switch (Os.arch()) {
            case "x86_64" -> "x64";
            case "arm64" -> "aarch64";
            case "x86" -> "x32";
            case "arm32" -> "arm";
            default -> Os.arch();
        };
        Path dir = ctx.home().runtimes().resolve("temurin-" + major).resolve(os + "-" + arch);
        Path marker = dir.resolve(".verity-complete");
        if (Files.isRegularFile(marker)) return executableIn(Path.of(Files.readString(marker).trim()));

        progress.stage("Downloading Eclipse Temurin " + major + " JRE");
        URI uri = URI.create("https://api.adoptium.net/v3/binary/latest/" + major + "/ga/" + os + "/" + arch
                + "/jre/hotspot/normal/eclipse");
        FileUtil.deleteRecursively(dir);
        Files.createDirectories(dir);
        try (InputStream in = ctx.http().getStream(uri)) {
            if (Os.current() == Os.WINDOWS) unzip(in, dir);
            else untar(in, dir);
        }
        Path home;
        try (var list = Files.list(dir)) {
            home = list.filter(Files::isDirectory).findFirst().orElse(dir);
        }
        if (Files.isDirectory(home.resolve("Contents/Home"))) home = home.resolve("Contents/Home");
        Files.writeString(marker, home.toString());
        return executableIn(home);
    }

    static void unzip(InputStream in, Path dir) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path target = dir.resolve(e.getName()).normalize();
                if (!target.startsWith(dir)) throw new IOException("Bad zip entry " + e.getName());
                if (e.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void untar(InputStream in, Path dir) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(in))) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                Path target = dir.resolve(e.getName()).normalize();
                if (!target.startsWith(dir)) throw new IOException("Bad tar entry " + e.getName());
                if (e.isDirectory()) Files.createDirectories(target);
                else if (e.isSymbolicLink()) {
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Path.of(e.getLinkName()));
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                    if ((e.getMode() & 0100) != 0) target.toFile().setExecutable(true, false);
                }
            }
        }
    }
}

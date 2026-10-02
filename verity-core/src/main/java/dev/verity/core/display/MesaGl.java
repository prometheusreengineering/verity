package dev.verity.core.display;

import com.fasterxml.jackson.databind.JsonNode;
import dev.verity.core.install.InstallContext;
import dev.verity.core.net.Downloader;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Json;
import dev.verity.core.util.Os;
import dev.verity.core.util.Progress;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

/**
 * Mesa software OpenGL for Windows (pal1000/mesa-dist-win). Windows resolves {@code opengl32.dll} from the
 * directory of {@code java.exe} first, so Verity keeps a copy of the Java runtime with Mesa's DLLs in its
 * {@code bin} folder and launches Mesa-backed clients from there. Works for LWJGL 2 and 3 alike.
 */
public final class MesaGl {
    static final String LATEST = "https://api.github.com/repos/pal1000/mesa-dist-win/releases/latest";

    private final InstallContext ctx;

    public MesaGl(InstallContext ctx) {
        this.ctx = ctx;
    }

    /** Returns a java executable inside a Mesa-enabled copy of {@code javaExe}'s runtime. */
    public Path javaWithMesa(Path javaExe, Progress progress) throws Exception {
        if (Os.current() != Os.WINDOWS) return javaExe;
        Path dlls = ensureDlls(progress);
        Path bin = javaExe.toAbsolutePath().getParent();
        Path runtime = bin.getParent();
        Path copy = runtime.resolveSibling(runtime.getFileName() + "-mesa");
        return ctx.locks().withLock("mesa-runtime-" + runtime.getFileName(), () -> {
            Path marker = copy.resolve(".verity-mesa");
            if (!Files.isRegularFile(marker)) {
                FileUtil.deleteRecursively(copy);
                FileUtil.copyRecursively(runtime, copy);
                try (var list = Files.list(dlls)) {
                    for (Path dll : list.toList()) {
                        Files.copy(dll, copy.resolve("bin").resolve(dll.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(marker, dlls.toString());
            }
            return copy.resolve("bin").resolve(javaExe.getFileName().toString());
        });
    }

    Path ensureDlls(Progress progress) throws Exception {
        Path dir = ctx.home().tools().resolve("mesa");
        Path x64 = dir.resolve("x64");
        return ctx.locks().withLock("mesa", () -> {
            if (Files.isRegularFile(x64.resolve("opengl32.dll"))) return x64;
            progress.stage("Downloading Mesa software OpenGL");
            // Authenticated when GITHUB_TOKEN is set (CI), avoiding the anonymous API rate limit.
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Accept", "application/vnd.github+json");
            String token = System.getenv("GITHUB_TOKEN");
            if (token != null && !token.isBlank()) headers.put("Authorization", "Bearer " + token);
            var response = ctx.http().get(URI.create(LATEST), headers);
            if (response.statusCode() / 100 != 2) throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + LATEST);
            JsonNode release = Json.tree(response.body());
            String url = null;
            for (JsonNode a : release.path("assets")) {
                String n = a.path("name").asText();
                if (n.endsWith("-release-msvc.7z") || (url == null && n.endsWith("release-mingw.7z"))) {
                    url = a.path("browser_download_url").asText();
                    if (n.endsWith("-release-msvc.7z")) break;
                }
            }
            if (url == null) throw new IOException("No Mesa build found in " + LATEST);
            Path archive = dir.resolve("mesa.7z");
            ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(url)), archive, null, -1, true));
            extractX64(archive, x64);
            Files.deleteIfExists(archive);
            return x64;
        });
    }

    /** Only the OpenGL pieces; the archive also carries Vulkan/OpenCL drivers we must not put next to java.exe. */
    static boolean isGlDll(String file) {
        String f = file.toLowerCase();
        return f.endsWith(".dll") && !f.contains("/")
                && (f.startsWith("opengl32") || f.startsWith("libgallium") || f.startsWith("libglapi") || f.startsWith("dxil"));
    }

    static void extractX64(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        try (SevenZFile z = SevenZFile.builder().setPath(archive).get()) {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !name.startsWith("x64/") || !isGlDll(name.substring(4))) continue;
                Path out = target.resolve(name.substring(4)).normalize();
                if (!out.startsWith(target)) continue;
                try (var in = z.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

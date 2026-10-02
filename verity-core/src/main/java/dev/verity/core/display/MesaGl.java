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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Mesa software OpenGL for Windows (pal1000/mesa-dist-win). Windows resolves {@code opengl32.dll} from the
 * directory of {@code java.exe} first, so Verity keeps a copy of the Java runtime with Mesa's DLLs in its
 * {@code bin} folder and launches Mesa-backed clients from there. Works for LWJGL 2 and 3 alike.
 */
public final class MesaGl {
    static final String LATEST = "https://api.github.com/repos/pal1000/mesa-dist-win/releases/latest";
    /** Official standalone 7-Zip extractor; Mesa's archives use the BCJ2 filter, which only 7-Zip itself decodes. */
    static final String SEVEN_ZR = "https://www.7-zip.org/a/7zr.exe";

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
            Path sevenZip = sevenZip(progress);
            Path tmp = dir.resolve("extract");
            try {
                FileUtil.deleteRecursively(tmp);
                progress.stage("Extracting Mesa");
                extract(sevenZip, archive, tmp, dir.resolve("extract.log"));
                copyGlDlls(tmp.resolve("x64"), x64);
            } finally {
                FileUtil.deleteRecursively(tmp);
                Files.deleteIfExists(archive);
            }
            if (!Files.isRegularFile(x64.resolve("opengl32.dll"))) throw new IOException("Mesa archive had no x64/opengl32.dll");
            return x64;
        });
    }

    /** An installed 7-Zip, else the standalone {@code 7zr.exe} downloaded into the tools folder. */
    Path sevenZip(Progress progress) throws IOException {
        Path found = findSevenZip(System.getenv());
        if (found != null) return found;
        Path exe = ctx.home().tools().resolve("7zip").resolve("7zr.exe");
        if (!Files.isRegularFile(exe)) {
            progress.stage("Downloading 7-Zip extractor");
            ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(SEVEN_ZR)), exe, null, -1, true));
        }
        return exe;
    }

    /** Looks on PATH, then in the default 7-Zip install folders. */
    static Path findSevenZip(Map<String, String> env) {
        List<Path> candidates = new ArrayList<>();
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String dir : path.split(java.io.File.pathSeparator)) {
            String d = dir.trim().replace("\"", "");
            if (d.isEmpty()) continue;
            for (String name : new String[] {"7z.exe", "7za.exe", "7zr.exe"}) {
                try {
                    candidates.add(Path.of(d, name));
                } catch (java.nio.file.InvalidPathException ignored) {
                    // malformed PATH entry
                }
            }
        }
        for (String var : new String[] {"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"}) {
            String pf = env.get(var);
            if (pf != null && !pf.isBlank()) candidates.add(Path.of(pf, "7-Zip", "7z.exe"));
        }
        for (Path c : candidates) if (Files.isRegularFile(c)) return c;
        return null;
    }

    static void extract(Path sevenZip, Path archive, Path target, Path log) throws IOException, InterruptedException {
        Files.createDirectories(target);
        Process p = new ProcessBuilder(sevenZip.toString(), "x", archive.toString(), "-o" + target, "-y")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("7-Zip timed out extracting " + archive);
        }
        if (p.exitValue() != 0) {
            throw new IOException("7-Zip failed (exit " + p.exitValue() + ") extracting " + archive + ": " + Files.readString(log).trim());
        }
    }

    static void copyGlDlls(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) throw new IOException("Mesa archive has no x64 folder");
        Files.createDirectories(to);
        try (var list = Files.list(from)) {
            for (Path f : list.toList()) {
                String name = f.getFileName().toString();
                if (Files.isRegularFile(f) && isGlDll(name)) Files.copy(f, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Only the OpenGL pieces; the archive also carries Vulkan/OpenCL drivers we must not put next to java.exe. */
    static boolean isGlDll(String file) {
        String f = file.toLowerCase();
        return f.endsWith(".dll") && !f.contains("/")
                && (f.startsWith("opengl32") || f.startsWith("libgallium") || f.startsWith("libglapi") || f.startsWith("dxil"));
    }
}

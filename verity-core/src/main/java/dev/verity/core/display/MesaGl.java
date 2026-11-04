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
    static final String RELEASES = "https://api.github.com/repos/pal1000/mesa-dist-win/releases?per_page=5";
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
            // Every client of a run asks; after a failure the rest skip straight to the fallback for a while, but a
            // transient failure (rate limit, network) must not stick for the lifetime of a long-running UI.
            Failure failed = FAILURES.get(dir);
            if (failed != null && System.nanoTime() - failed.atNanos() < RETRY_AFTER_NANOS) throw failed.error();
            try {
                install(dir, x64, progress);
                FAILURES.remove(dir);
                return x64;
            } catch (IOException e) {
                FAILURES.put(dir, new Failure(e, System.nanoTime()));
                throw e;
            }
        });
    }

    private record Failure(IOException error, long atNanos) {
    }

    static final long RETRY_AFTER_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    private static final Map<Path, Failure> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

    private void install(Path dir, Path x64, Progress progress) throws Exception {
        progress.stage("Downloading Mesa software OpenGL");
        // Authenticated when GITHUB_TOKEN is set (CI), avoiding the anonymous API rate limit.
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) headers.put("Authorization", "Bearer " + token);
        var response = ctx.http().get(URI.create(RELEASES), headers);
        if (response.statusCode() / 100 != 2) throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + RELEASES);
        List<String> urls = candidates(Json.tree(response.body()));
        if (urls.isEmpty()) throw new IOException("No Mesa build found in " + RELEASES);
        Path sevenZip = sevenZip(progress);
        Path archive = dir.resolve("mesa.7z");
        Path tmp = dir.resolve("extract");
        List<String> problems = new ArrayList<>();
        for (String url : urls.subList(0, Math.min(urls.size(), MAX_ATTEMPTS))) {
            String file = url.substring(url.lastIndexOf('/') + 1);
            try {
                FileUtil.deleteRecursively(tmp);
                ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(url)), archive, null, -1, true));
                progress.stage("Extracting Mesa (" + file + ")");
                extract(sevenZip, archive, tmp, dir.resolve("extract.log"));
                // Some releases ship builds without OpenGL (e.g. 26.2.6 MSVC, a known d3d12 build regression).
                if (!Files.isRegularFile(tmp.resolve("x64").resolve("opengl32.dll"))) {
                    problems.add(file + " has no x64/opengl32.dll");
                    continue;
                }
                FileUtil.deleteRecursively(x64);
                copyGlDlls(tmp.resolve("x64"), x64);
                return;
            } catch (IOException e) {
                problems.add(file + ": " + e.getMessage());
            } finally {
                FileUtil.deleteRecursively(tmp);
                Files.deleteIfExists(archive);
            }
        }
        throw new IOException("No usable Mesa build (" + String.join("; ", problems) + ")");
    }

    static final int MAX_ATTEMPTS = 4;

    /** Release archives to try, newest release first, MSVC before MinGW within a release. */
    static List<String> candidates(JsonNode releases) {
        List<String> urls = new ArrayList<>();
        for (JsonNode release : releases) {
            if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean()) continue;
            for (String suffix : new String[] {"-release-msvc.7z", "-release-mingw.7z"}) {
                for (JsonNode a : release.path("assets")) {
                    if (a.path("name").asText().endsWith(suffix)) urls.add(a.path("browser_download_url").asText());
                }
            }
        }
        return urls;
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

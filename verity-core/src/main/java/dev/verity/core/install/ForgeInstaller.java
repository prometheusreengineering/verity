package dev.verity.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.verity.core.meta.Library;
import dev.verity.core.meta.LoaderType;
import dev.verity.core.meta.NeoForgeMeta;
import dev.verity.core.meta.ForgeMeta;
import dev.verity.core.meta.Rule;
import dev.verity.core.meta.VersionJson;
import dev.verity.core.net.Downloader;
import dev.verity.core.net.ProxyConfig;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Hashing;
import dev.verity.core.util.Json;
import dev.verity.core.util.MavenCoordinate;
import dev.verity.core.util.Os;
import dev.verity.core.util.Progress;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs Forge and NeoForge clients from their official installer jars without running the installer GUI:
 * the legacy format (≤1.12.2, {@code versionInfo} + universal jar) and the processor-based format used since
 * 1.13 (and by NeoForge), whose processors Verity runs itself.
 */
public final class ForgeInstaller {
    private final InstallContext ctx;
    private final GameInstaller game;

    public ForgeInstaller(InstallContext ctx, GameInstaller game) {
        this.ctx = ctx;
        this.game = game;
    }

    public InstalledVersion install(LoaderType type, String mc, String loaderVersion, Progress progress) throws Exception {
        String url = switch (type) {
            case FORGE -> ForgeMeta.installerUrl(ctx.meta().forge().mavenVersion(mc, loaderVersion));
            case NEOFORGE -> NeoForgeMeta.installerUrl(loaderVersion);
            default -> throw new IllegalArgumentException(type + " is not installer based");
        };
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path installer = ctx.home().installers().resolve(fileName);
        Path marker = ctx.home().installers().resolve(fileName + ".installed");
        return ctx.locks().withLock("install-" + fileName, () -> {
            if (Files.isRegularFile(marker)) {
                String id = Files.readString(marker).trim();
                if (Files.isRegularFile(ctx.resolver().jsonPath(id))) return game.complete(id, progress);
            }
            game.ensureVanilla(mc, progress);
            progress.stage("Downloading " + type.displayName() + " " + loaderVersion + " installer");
            ctx.downloader().download(new Downloader.Artifact(List.of(URI.create(url)), installer, null, -1, true));
            String id;
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                JsonNode profile = readJson(zip, "install_profile.json");
                if (profile.has("versionInfo")) id = installLegacy(zip, profile, mc);
                else id = installModern(zip, installer, profile, mc, progress);
            }
            InstalledVersion v = game.complete(id, progress);
            Files.writeString(marker, id);
            return v;
        });
    }

    // ---------------------------------------------------------------- legacy (1.7.10 – 1.12.2)

    String installLegacy(ZipFile zip, JsonNode profile, String mc) throws IOException {
        JsonNode install = profile.path("install");
        ObjectNode versionInfo = (ObjectNode) profile.path("versionInfo");
        String id = versionInfo.path("id").asText(install.path("target").asText());
        if (!versionInfo.hasNonNull("inheritsFrom")) versionInfo.put("inheritsFrom", mc);
        if (!versionInfo.hasNonNull("jar")) versionInfo.put("jar", mc);
        // Old installers declare the Forge universal jar as a library without a usable URL; ship it from the installer.
        String filePath = install.path("filePath").asText(null);
        String path = install.path("path").asText(null);
        if (filePath != null && path != null) {
            Path target = ctx.home().libraries().resolve(MavenCoordinate.parse(path).path());
            extractEntry(zip, filePath, target);
        }
        Path json = ctx.resolver().jsonPath(id);
        FileUtil.writeAtomically(json, Json.MAPPER.writeValueAsBytes(versionInfo));
        return id;
    }

    // ---------------------------------------------------------------- modern (1.13+, NeoForge)

    String installModern(ZipFile zip, Path installerPath, JsonNode profile, String mc, Progress progress) throws Exception {
        String jsonEntry = profile.path("json").asText("/version.json");
        JsonNode versionNode = readJson(zip, stripSlash(jsonEntry));
        String id = profile.path("version").asText(versionNode.path("id").asText());

        // 1. Artifacts bundled in the installer (forge universal/client jars, etc.)
        extractMaven(zip);

        // 2. version JSON
        FileUtil.writeAtomically(ctx.resolver().jsonPath(id), Json.MAPPER.writeValueAsBytes(versionNode));

        // 3. Libraries the processors need
        VersionJson processorLibs = new VersionJson();
        processorLibs.libraries = new ArrayList<>();
        for (JsonNode l : profile.path("libraries")) processorLibs.libraries.add(Json.MAPPER.treeToValue(l, Library.class));
        List<Downloader.Artifact> downloads = new ArrayList<>();
        for (Libraries.Resolved r : Libraries.resolve(processorLibs, Rule.Environment.current(), ctx.home().libraries())) {
            if (!r.localOnly()) downloads.add(r.artifact());
        }
        progress.stage("Downloading installer libraries");
        ctx.downloader().downloadAll(downloads, progress);

        // 4. Processors
        JsonNode processors = profile.path("processors");
        if (processors.isArray() && !processors.isEmpty()) {
            Path work = Files.createTempDirectory(ctx.home().installers(), "work-");
            try {
                Map<String, String> data = buildData(zip, installerPath, profile, mc, work);
                VersionJson base = ctx.meta().mojang().vanillaVersion(mc);
                String component = base.javaVersion != null && base.javaVersion.component != null ? base.javaVersion.component : "jre-legacy";
                int major = base.javaVersion != null && base.javaVersion.majorVersion > 0 ? base.javaVersion.majorVersion : 8;
                Path java = ctx.java().javaFor(component, major, progress);
                int i = 0;
                int total = processors.size();
                for (JsonNode p : processors) {
                    i++;
                    if (!appliesToClient(p)) continue;
                    progress.stage("Running installer processor " + i + "/" + total);
                    runProcessor(p, data, java, installerPath.resolveSibling(installerPath.getFileName() + ".log"));
                }
            } finally {
                FileUtil.deleteRecursively(work);
            }
        }
        return id;
    }

    static boolean appliesToClient(JsonNode processor) {
        JsonNode sides = processor.path("sides");
        if (!sides.isArray() || sides.isEmpty()) return true;
        for (JsonNode s : sides) if (s.asText().equals("client")) return true;
        return false;
    }

    Map<String, String> buildData(ZipFile zip, Path installerPath, JsonNode profile, String mc, Path work) throws IOException {
        Map<String, String> data = new HashMap<>();
        var it = profile.path("data").properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String value = e.getValue().path("client").asText(null);
            if (value == null) continue;
            data.put(e.getKey(), resolveDataValue(zip, value, work));
        }
        data.put("SIDE", "client");
        data.put("MINECRAFT_JAR", game.clientJar(mc).toString());
        data.put("MINECRAFT_VERSION", mc);
        data.put("ROOT", ctx.home().root().toString());
        data.put("INSTALLER", installerPath.toString());
        data.put("LIBRARY_DIR", ctx.home().libraries().toString());
        return data;
    }

    String resolveDataValue(ZipFile zip, String value, Path work) throws IOException {
        if (value.startsWith("[") && value.endsWith("]")) return artifactPath(value).toString();
        if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1);
        if (value.startsWith("/")) {
            Path target = work.resolve(stripSlash(value)).normalize();
            if (!target.startsWith(work)) throw new IOException("Bad installer data path " + value);
            extractEntry(zip, stripSlash(value), target);
            return target.toString();
        }
        return value;
    }

    Path artifactPath(String coord) {
        return ctx.home().libraries().resolve(MavenCoordinate.parse(coord).path());
    }

    void runProcessor(JsonNode p, Map<String, String> data, Path java, Path log) throws Exception {
        Path jar = artifactPath(p.path("jar").asText());
        List<Path> cp = new ArrayList<>();
        cp.add(jar);
        for (JsonNode c : p.path("classpath")) cp.add(artifactPath(c.asText()));
        for (Path c : cp) if (!Files.isRegularFile(c)) throw new IOException("Processor classpath entry missing: " + c);

        Map<String, String> outputs = new LinkedHashMap<>();
        var outs = p.path("outputs").properties().iterator();
        while (outs.hasNext()) {
            Map.Entry<String, JsonNode> e = outs.next();
            outputs.put(replaceTokens(e.getKey(), data), replaceTokens(e.getValue().asText(), data));
        }
        if (!outputs.isEmpty() && outputsValid(outputs)) return;

        String mainClass;
        try (JarFile jf = new JarFile(jar.toFile())) {
            mainClass = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if (mainClass == null) throw new IOException("Processor " + jar + " has no Main-Class");

        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.addAll(ProxyConfig.jvmArgs());
        cmd.add("-cp");
        cmd.add(String.join(Os.current().classpathSeparator(), cp.stream().map(Path::toString).toList()));
        cmd.add(mainClass);
        for (JsonNode a : p.path("args")) cmd.add(processArg(a.asText(), data));

        Files.writeString(log, "\n$ " + String.join(" ", cmd) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("Installer processor " + mainClass + " failed with exit code " + exit + "; see " + log + "\n" + tail(log));
        }
        if (!outputs.isEmpty() && !outputsValid(outputs)) throw new IOException("Processor " + mainClass + " produced unexpected outputs");
    }

    String processArg(String arg, Map<String, String> data) throws IOException {
        if (arg.startsWith("[") && arg.endsWith("]")) return artifactPath(arg).toString();
        return replaceTokens(arg, data);
    }

    boolean outputsValid(Map<String, String> outputs) throws IOException {
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            Path f = Path.of(e.getKey());
            if (!Files.isRegularFile(f)) return false;
            String expected = e.getValue().replace("'", "");
            if (!expected.equalsIgnoreCase(Hashing.sha1(f))) return false;
        }
        return true;
    }

    /** Forge installer token syntax: {@code {KEY}} from data, {@code 'literal'} segments, backslash escapes. */
    static String replaceTokens(String s, Map<String, String> data) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length()) throw new IOException("Illegal trailing escape in " + s);
                out.append(s.charAt(++i));
            } else if (c == '{' || c == '\'') {
                char close = c == '{' ? '}' : '\'';
                StringBuilder key = new StringBuilder();
                int j = i + 1;
                for (; ; j++) {
                    if (j >= s.length()) throw new IOException("Unclosed " + c + " in " + s);
                    char d = s.charAt(j);
                    if (d == '\\' && j + 1 < s.length()) key.append(s.charAt(++j));
                    else if (d == close) break;
                    else key.append(d);
                }
                i = j;
                if (c == '\'') {
                    out.append(key);
                } else {
                    String v = data.get(key.toString());
                    if (v == null) throw new IOException("Unknown installer token {" + key + "}");
                    out.append(v);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- zip helpers

    void extractMaven(ZipFile zip) throws IOException {
        Path libs = ctx.home().libraries();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory() || !e.getName().startsWith("maven/")) continue;
            Path target = libs.resolve(e.getName().substring("maven/".length())).normalize();
            if (!target.startsWith(libs)) continue;
            if (Files.isRegularFile(target) && Files.size(target) == e.getSize()) continue;
            extractEntry(zip, e.getName(), target);
        }
    }

    static void extractEntry(ZipFile zip, String name, Path target) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("Installer is missing " + name);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".extract");
        try (InputStream in = zip.getInputStream(e)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        FileUtil.moveAtomically(tmp, target);
    }

    static JsonNode readJson(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("Installer has no " + name);
        try (InputStream in = zip.getInputStream(e)) {
            return Json.MAPPER.readTree(in);
        }
    }

    private static String stripSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }
}

package ing.prometheusreengineer.verity.core.install;

import ing.prometheusreengineer.verity.core.meta.Argument;
import ing.prometheusreengineer.verity.core.meta.Library;
import ing.prometheusreengineer.verity.core.meta.VersionJson;
import ing.prometheusreengineer.verity.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads a version JSON from {@code versions/} and flattens its {@code inheritsFrom} chain. */
public final class VersionResolver {
    private final Path versionsDir;

    public VersionResolver(Path versionsDir) {
        this.versionsDir = versionsDir;
    }

    public Path jsonPath(String id) {
        return versionsDir.resolve(id).resolve(id + ".json");
    }

    public VersionJson load(String id) throws IOException {
        Path p = jsonPath(id);
        if (!Files.isRegularFile(p)) throw new IOException("Version " + id + " is not installed (" + p + " missing)");
        return Json.read(p, VersionJson.class);
    }

    /** Child-first chain: {@code [fabric-loader-x-1.20.1, 1.20.1]}. */
    public List<VersionJson> chain(String id) throws IOException {
        List<VersionJson> chain = new ArrayList<>();
        String next = id;
        while (next != null) {
            if (chain.size() > 8) throw new IOException("inheritsFrom chain too deep at " + next);
            VersionJson v = load(next);
            chain.add(v);
            next = v.inheritsFrom;
        }
        return chain;
    }

    public VersionJson resolve(String id) throws IOException {
        return merge(chain(id));
    }

    /** Merges a child-first chain into one launchable version. Child values win; argument lists concatenate parent-first. */
    public static VersionJson merge(List<VersionJson> chain) {
        VersionJson out = new VersionJson();
        VersionJson child = chain.get(0);
        out.id = child.id;
        out.inheritsFrom = null;
        // Child versions override parent libraries with the same versionless key. Entries within one JSON are all
        // kept: 1.14-1.18 list e.g. lwjgl-glfw 3.2.1 (macOS only) and 3.2.2 (everything else), and rules pick one.
        Map<String, List<Library>> libs = new LinkedHashMap<>();
        List<Argument> game = new ArrayList<>();
        List<Argument> jvm = new ArrayList<>();
        boolean anyModernArgs = false;
        for (VersionJson v : chain) {
            if (out.type == null) out.type = v.type;
            if (out.mainClass == null) out.mainClass = v.mainClass;
            if (out.minecraftArguments == null) out.minecraftArguments = v.minecraftArguments;
            if (out.assetIndex == null) out.assetIndex = v.assetIndex;
            if (out.assets == null) out.assets = v.assets;
            if (out.downloads == null) out.downloads = v.downloads;
            if (out.javaVersion == null) out.javaVersion = v.javaVersion;
            if (out.logging == null) out.logging = v.logging;
            if (out.releaseTime == null) out.releaseTime = v.releaseTime;
            if (out.jar == null) out.jar = v.jar;
            if (v.libraries != null) {
                java.util.Set<String> fromChildren = new java.util.HashSet<>(libs.keySet());
                for (Library l : v.libraries) {
                    String key = libraryKey(l);
                    if (!fromChildren.contains(key)) libs.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
                }
            }
            if (v.arguments != null) anyModernArgs = true;
        }
        // Parent arguments come first so loaders can append/override (matches the official launcher).
        for (int i = chain.size() - 1; i >= 0; i--) {
            VersionJson v = chain.get(i);
            if (v.arguments != null) {
                if (v.arguments.game != null) game.addAll(v.arguments.game);
                if (v.arguments.jvm != null) jvm.addAll(v.arguments.jvm);
            }
        }
        if (anyModernArgs) {
            out.arguments = new VersionJson.Arguments();
            out.arguments.game = game;
            out.arguments.jvm = jvm;
            // A loader that still uses minecraftArguments on a modern base would be odd; modern wins.
        }
        out.libraries = new ArrayList<>();
        for (List<Library> sameKey : libs.values()) out.libraries.addAll(sameKey);
        return out;
    }

    static String libraryKey(Library l) {
        String key;
        try {
            key = l.coordinate().versionlessKey();
        } catch (IllegalArgumentException e) {
            key = l.name;
        }
        return l.hasNatives() ? key + "#natives" : key;
    }

    /** Id of the version whose client jar this version runs on (root of the chain unless {@code jar} is set). */
    public static String baseJarId(List<VersionJson> chain) {
        for (VersionJson v : chain) if (v.jar != null) return v.jar;
        return chain.get(chain.size() - 1).id;
    }
}

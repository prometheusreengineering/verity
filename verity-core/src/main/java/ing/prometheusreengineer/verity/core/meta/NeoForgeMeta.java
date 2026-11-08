package ing.prometheusreengineer.verity.core.meta;

import ing.prometheusreengineer.verity.core.net.MetaCache;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge versions. 1.20.1 ships as {@code net.neoforged:forge:1.20.1-47.1.x}; later versions as
 * {@code net.neoforged:neoforge} where the version encodes Minecraft: {@code 20.4.x → 1.20.4},
 * {@code 21.0.x → 1.21}, and for year-based Minecraft {@code 26.1.0.x → 26.1}, {@code 26.1.1.x → 26.1.1}.
 */
public final class NeoForgeMeta implements LoaderMeta {
    public static final String HOST = "https://maven.neoforged.net/";
    public static final String MAVEN = HOST + "releases/";

    private final MetaCache cache;
    private final String host;

    public NeoForgeMeta(MetaCache cache) {
        this(cache, HOST);
    }

    NeoForgeMeta(MetaCache cache, String host) {
        this.cache = cache;
        this.host = host.endsWith("/") ? host : host + "/";
    }

    /**
     * Versions of {@code net/neoforged/<artifact>}, oldest first. NeoForge's Maven (Reposilite) doesn't always serve
     * maven-metadata.xml, so its version API is tried first and the XML is the fallback.
     */
    List<String> versions(String artifact) throws IOException {
        String api = host + "api/maven/versions/releases/net/neoforged/" + artifact;
        String xml = host + "releases/net/neoforged/" + artifact + "/maven-metadata.xml";
        IOException first;
        try {
            com.fasterxml.jackson.databind.JsonNode n = ing.prometheusreengineer.verity.core.util.Json.tree(cache.get(api)).path("versions");
            if (n.isArray() && !n.isEmpty()) {
                List<String> out = new ArrayList<>();
                n.forEach(v -> out.add(v.asText()));
                return out;
            }
            first = new IOException("no versions in " + api);
        } catch (IOException | RuntimeException e) {
            first = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        try {
            List<String> out = MavenMetadata.versions(cache.get(xml));
            if (!out.isEmpty()) return out;
            throw new IOException("no versions in " + xml);
        } catch (IOException e) {
            IOException both = new IOException("Cannot list NeoForge versions (" + first.getMessage() + "; " + e.getMessage() + ")", e);
            both.addSuppressed(first);
            throw both;
        }
    }

    @Override
    public LoaderType type() {
        return LoaderType.NEOFORGE;
    }

    /** Minecraft version a {@code net.neoforged:neoforge} version targets, or null if unparseable. */
    public static String gameVersionOf(String neoVersion) {
        String core = neoVersion.split("[-+]")[0];
        String[] p = core.split("\\.");
        if (p.length < 2) return null;
        try {
            int major = Integer.parseInt(p[0]);
            int minor = Integer.parseInt(p[1]);
            if (major >= 25) {
                // year scheme: <year>.<drop>.<hotfix>.<build>
                int hotfix = p.length >= 4 ? Integer.parseInt(p[2]) : 0;
                return major + "." + minor + (hotfix > 0 ? "." + hotfix : "");
            }
            return "1." + major + (minor > 0 ? "." + minor : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Map<String, List<String>> byGameVersion() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String v : versions("neoforge")) {
            String mc = gameVersionOf(v);
            if (mc != null) out.computeIfAbsent(mc, k -> new ArrayList<>()).add(v);
        }
        try {
            for (String v : versions("forge")) {
                if (v.startsWith("1.20.1-")) out.computeIfAbsent("1.20.1", k -> new ArrayList<>()).add(v);
            }
        } catch (IOException ignored) {
            // 1.20.1 support is optional
        }
        // Newest first, independent of the order the server lists them in.
        java.util.Comparator<String> byVersion = (a, b) -> ing.prometheusreengineer.verity.core.util.McVersion.compare(
                a.startsWith("1.20.1-") ? a.substring(7) : a, b.startsWith("1.20.1-") ? b.substring(7) : b);
        out.values().forEach(list -> list.sort(byVersion.reversed()));
        return out;
    }

    @Override
    public List<String> supportedGameVersions() throws IOException {
        return new ArrayList<>(byGameVersion().keySet());
    }

    @Override
    public List<String> loaderVersions(String mc) throws IOException {
        return byGameVersion().getOrDefault(mc, List.of());
    }

    @Override
    public String resolve(String mc, String spec) throws IOException {
        List<String> versions = loaderVersions(mc);
        if (versions.isEmpty()) throw new IOException("NeoForge does not support Minecraft " + mc);
        if (!LoaderMeta.isAlias(spec)) {
            if (versions.contains(spec)) return spec;
            if (versions.contains(mc + "-" + spec)) return mc + "-" + spec;
            throw new IOException("NeoForge " + spec + " is not available for Minecraft " + mc);
        }
        if (!"latest".equalsIgnoreCase(spec)) {
            for (String v : versions) if (!v.contains("-beta") && !v.contains("-alpha")) return v;
        }
        return versions.get(0);
    }

    public static boolean isLegacyForgeArtifact(String version) {
        return version.startsWith("1.20.1-");
    }

    public static String installerUrl(String version) {
        if (isLegacyForgeArtifact(version)) {
            return MAVEN + "net/neoforged/forge/" + version + "/forge-" + version + "-installer.jar";
        }
        return MAVEN + "net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar";
    }
}

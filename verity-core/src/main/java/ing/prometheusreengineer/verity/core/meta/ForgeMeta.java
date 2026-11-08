package ing.prometheusreengineer.verity.core.meta;

import com.fasterxml.jackson.databind.JsonNode;
import ing.prometheusreengineer.verity.core.net.MetaCache;
import ing.prometheusreengineer.verity.core.util.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MinecraftForge versions from its Maven metadata and promotions. Loader versions are the short Forge
 * version ({@code 47.2.0}, {@code 11.15.1.2318}); {@link #mavenVersion} maps back to the full Maven version
 * ({@code 1.8.9-11.15.1.2318-1.8.9}).
 */
public final class ForgeMeta implements LoaderMeta {
    public static final String MAVEN = "https://maven.minecraftforge.net/";
    public static final String METADATA = MAVEN + "net/minecraftforge/forge/maven-metadata.xml";
    public static final String PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    private final MetaCache cache;

    public ForgeMeta(MetaCache cache) {
        this.cache = cache;
    }

    @Override
    public LoaderType type() {
        return LoaderType.FORGE;
    }

    /** mc → full maven versions, newest first. */
    Map<String, List<String>> byGameVersion() throws IOException {
        return group(MavenMetadata.versions(cache.get(METADATA)));
    }

    static Map<String, List<String>> group(List<String> mavenVersions) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String v : mavenVersions) {
            int dash = v.indexOf('-');
            if (dash <= 0) continue;
            out.computeIfAbsent(v.substring(0, dash), k -> new ArrayList<>()).add(v);
        }
        out.values().forEach(Collections::reverse);
        return out;
    }

    /** {@code 1.8.9-11.15.1.2318-1.8.9 -> 11.15.1.2318}. */
    public static String shortVersion(String mc, String maven) {
        String s = maven.startsWith(mc + "-") ? maven.substring(mc.length() + 1) : maven;
        if (s.endsWith("-" + mc)) s = s.substring(0, s.length() - mc.length() - 1);
        return s;
    }

    @Override
    public List<String> supportedGameVersions() throws IOException {
        return new ArrayList<>(new LinkedHashSet<>(byGameVersion().keySet()));
    }

    @Override
    public List<String> loaderVersions(String mc) throws IOException {
        return byGameVersion().getOrDefault(mc, List.of()).stream().map(v -> shortVersion(mc, v)).toList();
    }

    @Override
    public String resolve(String mc, String spec) throws IOException {
        List<String> versions = loaderVersions(mc);
        if (versions.isEmpty()) throw new IOException("Forge does not support Minecraft " + mc);
        if (!LoaderMeta.isAlias(spec)) {
            String s = shortVersion(mc, spec);
            if (versions.contains(s)) return s;
            throw new IOException("Forge " + spec + " is not available for Minecraft " + mc);
        }
        try {
            JsonNode promos = Json.tree(cache.get(PROMOTIONS)).path("promos");
            if (!"latest".equalsIgnoreCase(spec)) {
                String rec = promos.path(mc + "-recommended").asText(null);
                if (rec != null && versions.contains(rec)) return rec;
            }
            String latest = promos.path(mc + "-latest").asText(null);
            if (latest != null && versions.contains(latest)) return latest;
        } catch (IOException ignored) {
            // promotions are a nicety; fall back to newest
        }
        return versions.get(0);
    }

    public String mavenVersion(String mc, String shortVersion) throws IOException {
        for (String v : byGameVersion().getOrDefault(mc, List.of())) {
            if (shortVersion(mc, v).equals(shortVersion)) return v;
        }
        throw new IOException("Unknown Forge version " + shortVersion + " for Minecraft " + mc);
    }

    public static String installerUrl(String mavenVersion) {
        return MAVEN + "net/minecraftforge/forge/" + mavenVersion + "/forge-" + mavenVersion + "-installer.jar";
    }
}

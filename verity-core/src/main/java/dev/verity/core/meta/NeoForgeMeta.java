package dev.verity.core.meta;

import dev.verity.core.net.MetaCache;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge versions. 1.20.1 ships as {@code net.neoforged:forge:1.20.1-47.1.x}; later versions as
 * {@code net.neoforged:neoforge} where the version encodes Minecraft: {@code 20.4.x → 1.20.4},
 * {@code 21.0.x → 1.21}, and for year-based Minecraft {@code 26.1.0.x → 26.1}, {@code 26.1.1.x → 26.1.1}.
 */
public final class NeoForgeMeta implements LoaderMeta {
    public static final String MAVEN = "https://maven.neoforged.net/releases/";
    public static final String NEOFORGE_METADATA = MAVEN + "net/neoforged/neoforge/maven-metadata.xml";
    public static final String LEGACY_METADATA = MAVEN + "net/neoforged/forge/maven-metadata.xml";

    private final MetaCache cache;

    public NeoForgeMeta(MetaCache cache) {
        this.cache = cache;
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
        for (String v : MavenMetadata.versions(cache.get(NEOFORGE_METADATA))) {
            String mc = gameVersionOf(v);
            if (mc != null) out.computeIfAbsent(mc, k -> new ArrayList<>()).add(v);
        }
        try {
            for (String v : MavenMetadata.versions(cache.get(LEGACY_METADATA))) {
                if (v.startsWith("1.20.1-")) out.computeIfAbsent("1.20.1", k -> new ArrayList<>()).add(v);
            }
        } catch (IOException ignored) {
            // 1.20.1 support is optional
        }
        out.values().forEach(Collections::reverse);
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

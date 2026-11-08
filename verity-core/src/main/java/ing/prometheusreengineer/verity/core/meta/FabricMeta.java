package ing.prometheusreengineer.verity.core.meta;

import com.fasterxml.jackson.databind.JsonNode;
import ing.prometheusreengineer.verity.core.net.MetaCache;
import ing.prometheusreengineer.verity.core.util.Json;
import ing.prometheusreengineer.verity.core.util.McVersion;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fabric's meta API (meta.fabricmc.net). */
public final class FabricMeta implements LoaderMeta {
    public static final String BASE = "https://meta.fabricmc.net/v2";

    private final MetaCache cache;

    public FabricMeta(MetaCache cache) {
        this.cache = cache;
    }

    @Override
    public LoaderType type() {
        return LoaderType.FABRIC;
    }

    @Override
    public List<String> supportedGameVersions() throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode n : Json.tree(cache.get(BASE + "/versions/game"))) {
            String v = n.path("version").asText();
            if (n.path("stable").asBoolean(false) && McVersion.isRelease(v)) out.add(v);
        }
        return out;
    }

    /** Entries for {@code mc}, newest first, as {version, stable}. */
    List<JsonNode> entries(String mc) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : Json.tree(cache.get(BASE + "/versions/loader/" + enc(mc)))) out.add(n.path("loader"));
        return out;
    }

    @Override
    public List<String> loaderVersions(String mc) throws IOException {
        return entries(mc).stream().map(n -> n.path("version").asText()).toList();
    }

    @Override
    public String resolve(String mc, String spec) throws IOException {
        List<JsonNode> entries = entries(mc);
        if (entries.isEmpty()) throw new IOException("Fabric does not support Minecraft " + mc);
        if (!LoaderMeta.isAlias(spec)) {
            for (JsonNode n : entries) if (n.path("version").asText().equals(spec)) return spec;
            throw new IOException("Fabric loader " + spec + " is not available for Minecraft " + mc);
        }
        if (!"latest".equalsIgnoreCase(spec)) {
            for (JsonNode n : entries) if (n.path("stable").asBoolean(false)) return n.path("version").asText();
        }
        return entries.get(0).path("version").asText();
    }

    public String profileUrl(String mc, String loader) {
        return BASE + "/versions/loader/" + enc(mc) + "/" + enc(loader) + "/profile/json";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

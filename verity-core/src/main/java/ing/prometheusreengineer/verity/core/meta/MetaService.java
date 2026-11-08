package ing.prometheusreengineer.verity.core.meta;

import ing.prometheusreengineer.verity.core.net.MetaCache;
import ing.prometheusreengineer.verity.core.util.McVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Entry point for all version listings. */
public final class MetaService {
    private final MojangMeta mojang;
    private final Map<LoaderType, LoaderMeta> loaders = new EnumMap<>(LoaderType.class);

    public MetaService(MojangMeta mojang, MetaCache cache) {
        this.mojang = mojang;
        loaders.put(LoaderType.FABRIC, new FabricMeta(cache));
        loaders.put(LoaderType.FORGE, new ForgeMeta(cache));
        loaders.put(LoaderType.NEOFORGE, new NeoForgeMeta(cache));
    }

    public MojangMeta mojang() {
        return mojang;
    }

    public LoaderMeta loader(LoaderType type) {
        LoaderMeta m = loaders.get(type);
        if (m == null) throw new IllegalArgumentException("No loader metadata for " + type);
        return m;
    }

    public FabricMeta fabric() {
        return (FabricMeta) loaders.get(LoaderType.FABRIC);
    }

    public ForgeMeta forge() {
        return (ForgeMeta) loaders.get(LoaderType.FORGE);
    }

    public NeoForgeMeta neoforge() {
        return (NeoForgeMeta) loaders.get(LoaderType.NEOFORGE);
    }

    /** Release ids available for {@code type}, newest first. */
    public List<String> gameVersions(LoaderType type) throws IOException {
        List<String> releases = mojang.manifest().releases();
        if (type == LoaderType.VANILLA) return releases;
        List<String> supported = loader(type).supportedGameVersions();
        List<String> out = new ArrayList<>();
        for (String r : releases) if (supported.contains(r)) out.add(r);
        out.sort(McVersion.COMPARATOR.reversed());
        return out;
    }
}

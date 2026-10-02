package dev.verity.core.run;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.meta.MetaService;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.util.McVersion;
import dev.verity.core.util.VersionPredicate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Expands a profile's explicit targets and selectors into concrete {@link Target}s. */
public final class TargetExpander {
    /** Source of available versions; separated for testability. */
    public interface Catalog {
        /** Releases supported by {@code loader}, any order. */
        List<String> gameVersions(LoaderType loader) throws IOException;

        String latestRelease() throws IOException;

        static Catalog of(MetaService meta) {
            return new Catalog() {
                @Override
                public List<String> gameVersions(LoaderType loader) throws IOException {
                    return meta.gameVersions(loader);
                }

                @Override
                public String latestRelease() throws IOException {
                    return meta.mojang().manifest().latest.release;
                }
            };
        }
    }

    private final Catalog catalog;

    public TargetExpander(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<Target> expand(Profile profile) throws IOException, ProfileException {
        Map<String, Target> out = new LinkedHashMap<>();
        for (Profile.TargetSpec spec : profile.targets) {
            if (!spec.enabled) continue;
            if (spec.select != null) {
                for (Target t : select(spec.select)) out.putIfAbsent(t.id(), t);
            } else {
                if (spec.minecraft == null || spec.minecraft.isBlank()) throw new ProfileException("Target without a Minecraft version: " + spec);
                LoaderType loader = spec.loader == null ? LoaderType.VANILLA : spec.loader;
                String mc = spec.minecraft.trim();
                if (mc.equalsIgnoreCase("latest")) mc = latestFor(loader);
                Target t = new Target(mc, loader, loader == LoaderType.VANILLA ? null : spec.loaderVersion);
                out.putIfAbsent(t.id(), t);
            }
        }
        return new ArrayList<>(out.values());
    }

    private String latestFor(LoaderType loader) throws IOException, ProfileException {
        if (loader == LoaderType.VANILLA) return catalog.latestRelease();
        List<String> versions = sortedDesc(catalog.gameVersions(loader));
        if (versions.isEmpty()) throw new ProfileException("No Minecraft versions available for " + loader.displayName());
        return versions.get(0);
    }

    List<Target> select(Profile.Selector sel) throws IOException, ProfileException {
        List<LoaderType> loaders = sel.loaders == null || sel.loaders.isEmpty() ? List.of(LoaderType.VANILLA) : sel.loaders;
        List<Target> out = new ArrayList<>();
        for (LoaderType loader : loaders) {
            List<String> versions = sortedDesc(catalog.gameVersions(loader));
            List<String> picked = new ArrayList<>();
            String spec = sel.minecraft == null ? "*" : sel.minecraft.trim();
            if (spec.equalsIgnoreCase("latest")) {
                if (!versions.isEmpty()) picked.add(versions.get(0));
            } else {
                VersionPredicate p = VersionPredicate.parse(spec);
                for (String v : versions) if (p.test(v)) picked.add(v);
            }
            if (sel.latestPatchOnly) {
                Set<String> lines = new LinkedHashSet<>();
                List<String> newest = new ArrayList<>();
                for (String v : picked) if (lines.add(McVersion.minorLine(v))) newest.add(v);
                picked = newest;
            }
            if (sel.limit > 0 && picked.size() > sel.limit) picked = picked.subList(0, sel.limit);
            for (String v : picked) out.add(new Target(v, loader, loader == LoaderType.VANILLA ? null : sel.loaderVersion));
        }
        return out;
    }

    private static List<String> sortedDesc(List<String> versions) {
        List<String> v = new ArrayList<>(versions.stream().filter(McVersion::isRelease).toList());
        v.sort(McVersion.COMPARATOR.reversed());
        return v;
    }
}

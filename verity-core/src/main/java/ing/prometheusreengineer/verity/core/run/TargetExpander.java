package ing.prometheusreengineer.verity.core.run;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.meta.MetaService;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.util.McVersion;
import ing.prometheusreengineer.verity.core.util.VersionPredicate;
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

        /** (Minecraft version, loader) pairs a Modrinth project has files for. */
        default Set<Target> modrinthTargets(String project, String channel) throws IOException, ProfileException {
            throw new ProfileException("Modrinth is not available here");
        }

        static Catalog of(MetaService meta) {
            return of(meta, null);
        }

        static Catalog of(MetaService meta, ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver modrinth) {
            return new Catalog() {
                @Override
                public Set<Target> modrinthTargets(String project, String channel) throws IOException, ProfileException {
                    if (modrinth == null) return Catalog.super.modrinthTargets(project, channel);
                    return modrinth.supportedTargets(project, channel);
                }

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
                for (Target t : select(spec.select, channels(profile))) out.putIfAbsent(t.id(), t);
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

    /** Release channel per Modrinth project, taken from the profile's matching mod entry. */
    static Map<String, String> channels(Profile profile) {
        Map<String, String> out = new java.util.HashMap<>();
        for (Profile.ModEntry e : profile.mods) {
            if (e.fromModrinth() && e.channel != null && !e.channel.isBlank()) out.put(e.modrinth.trim().toLowerCase(), e.channel);
        }
        return out;
    }

    List<Target> select(Profile.Selector sel) throws IOException, ProfileException {
        return select(sel, Map.of());
    }

    List<Target> select(Profile.Selector sel, Map<String, String> channels) throws IOException, ProfileException {
        Set<String> modrinthPairs = null;
        if (sel.modrinth != null && !sel.modrinth.isEmpty()) {
            for (String project : sel.modrinth) {
                if (project == null || project.isBlank()) continue;
                Set<String> pairs = new LinkedHashSet<>();
                String channel = channels.getOrDefault(project.trim().toLowerCase(), "release");
                for (Target t : catalog.modrinthTargets(project.trim(), channel)) pairs.add(t.minecraft() + "|" + t.loader().id());
                if (modrinthPairs == null) modrinthPairs = pairs;
                else modrinthPairs.retainAll(pairs);
            }
        }
        List<LoaderType> loaders;
        if (sel.loaders != null && !sel.loaders.isEmpty()) {
            loaders = sel.loaders;
        } else if (modrinthPairs != null) {
            Set<String> present = new java.util.HashSet<>();
            for (String pair : modrinthPairs) present.add(pair.substring(pair.indexOf('|') + 1));
            loaders = java.util.Arrays.stream(LoaderType.values()).filter(l -> present.contains(l.id())).toList();
        } else {
            loaders = List.of(LoaderType.VANILLA);
        }
        List<Target> out = new ArrayList<>();
        for (LoaderType loader : loaders) {
            List<String> versions = sortedDesc(catalog.gameVersions(loader));
            if (modrinthPairs != null) {
                Set<String> pairs = modrinthPairs;
                versions = versions.stream().filter(v -> pairs.contains(v + "|" + loader.id())).toList();
            }
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

package ing.prometheusreengineer.verity.core.modrinth;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.util.McVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Picks the Modrinth file (and required dependencies) that fits a target. */
public final class ModrinthResolver {
    private final ModrinthClient client;

    public ModrinthResolver(ModrinthClient client) {
        this.client = client;
    }

    public ModrinthClient client() {
        return client;
    }

    /** A chosen file. {@code requiredBy} is null for the entry's own project. */
    public record Resolved(String projectId, String projectSlug, ModrinthVersion version, ModrinthVersion.File file, String requiredBy) {
        public boolean dependency() {
            return requiredBy != null;
        }
    }

    /** release < beta < alpha: a channel admits its own builds and every more stable one. */
    static int rank(String type) {
        if (type == null) return 0;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 0;
        };
    }

    public static boolean inChannel(ModrinthVersion v, String channel) {
        return rank(v.versionType) <= rank(channel == null || channel.isBlank() ? "release" : channel);
    }

    /** Modrinth loader names whose files a target can load, best match first. */
    public static List<String> loadersFor(Target t) {
        return switch (t.loader()) {
            case VANILLA -> List.of();
            case FABRIC -> List.of("fabric");
            case FORGE -> List.of("forge");
            // NeoForge 1.20.1 is a Forge fork and still loads Forge mods.
            case NEOFORGE -> t.minecraft().equals("1.20.1") ? List.of("neoforge", "forge") : List.of("neoforge");
        };
    }

    static LoaderType loaderType(String modrinthLoader) {
        return switch (modrinthLoader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> LoaderType.FABRIC;
            case "forge" -> LoaderType.FORGE;
            case "neoforge" -> LoaderType.NEOFORGE;
            default -> null;
        };
    }

    /** Newest version (input is newest first) for the target in the channel; a pin matches version number or id. */
    public static Optional<ModrinthVersion> pick(List<ModrinthVersion> versions, Target target, String channel, String pin) {
        for (String loader : loadersFor(target)) {
            for (ModrinthVersion v : versions) {
                if (pin != null && !pin.isBlank() && !(pin.equals(v.versionNumber) || pin.equals(v.id))) continue;
                if ((pin == null || pin.isBlank()) && !inChannel(v, channel)) continue;
                if (!v.gameVersions.contains(target.minecraft())) continue;
                if (v.loaders.stream().noneMatch(l -> l.equalsIgnoreCase(loader))) continue;
                if (v.primaryFile() == null) continue;
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * Files for {@code project} on {@code target}: the project's own file first, then required dependencies
     * (recursively). Empty when the project has nothing for this target.
     */
    public List<Resolved> resolve(String project, Target target, String channel, String pin, boolean dependencies)
            throws IOException, ProfileException {
        List<Resolved> out = new ArrayList<>();
        ModrinthProject p = client.project(project);
        Optional<ModrinthVersion> v = pick(client.versions(p.id), target, channel, pin);
        if (v.isEmpty()) return out;
        out.add(new Resolved(p.id, p.slug, v.get(), v.get().primaryFile(), null));
        if (dependencies) {
            Set<String> seen = new LinkedHashSet<>();
            seen.add(p.id);
            addDependencies(v.get(), p.slug, target, channel, seen, out, 0);
        }
        return out;
    }

    private void addDependencies(ModrinthVersion v, String requiredBy, Target target, String channel, Set<String> seen,
                                 List<Resolved> out, int depth) throws IOException, ProfileException {
        if (depth > 8) return;
        for (ModrinthVersion.Dependency d : v.dependencies) {
            if (!"required".equalsIgnoreCase(d.dependencyType)) continue;
            ModrinthVersion dv;
            ModrinthProject dp;
            if (d.versionId != null && !d.versionId.isBlank()) {
                dv = client.version(d.versionId);
                dp = client.project(d.projectId != null ? d.projectId : dv.projectId);
            } else if (d.projectId != null) {
                dp = client.project(d.projectId);
                dv = pick(client.versions(dp.id), target, channel, null)
                        .or(() -> pickQuietly(dp.id, target))
                        .orElse(null);
                if (dv == null) continue; // no build for this target; the loader itself reports the missing dependency
            } else {
                continue;
            }
            if (!seen.add(dp.id) || dv.primaryFile() == null) continue;
            out.add(new Resolved(dp.id, dp.slug, dv, dv.primaryFile(), requiredBy));
            addDependencies(dv, dp.slug, target, channel, seen, out, depth + 1);
        }
    }

    /** Dependencies may only have beta/alpha builds for a version; accept those rather than leaving them out. */
    private Optional<ModrinthVersion> pickQuietly(String projectId, Target target) {
        try {
            return pick(client.versions(projectId), target, "alpha", null);
        } catch (IOException | ProfileException e) {
            return Optional.empty();
        }
    }

    /** Every (release Minecraft version, loader) pair the project has a file for in the channel. */
    public Set<Target> supportedTargets(String project, String channel) throws IOException, ProfileException {
        Set<Target> out = new LinkedHashSet<>();
        for (ModrinthVersion v : client.versions(project)) {
            if (!inChannel(v, channel) || v.primaryFile() == null) continue;
            for (String l : v.loaders) {
                LoaderType type = loaderType(l);
                if (type == null) continue;
                for (String gv : v.gameVersions) {
                    if (McVersion.isRelease(gv)) out.add(new Target(gv, type, null));
                }
            }
        }
        return out;
    }
}

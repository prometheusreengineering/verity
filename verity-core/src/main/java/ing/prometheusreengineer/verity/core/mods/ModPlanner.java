package ing.prometheusreengineer.verity.core.mods;

import ing.prometheusreengineer.verity.core.home.VerityHome;
import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver;
import ing.prometheusreengineer.verity.core.net.Downloader;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, for every target, which jars it gets from every mod entry: local files (by metadata and rules) and Modrinth
 * projects (by the project's published versions). Modrinth files are only planned here; sessions download them.
 */
public final class ModPlanner {
    /** One jar for one target. {@code download} is set when the jar still has to be fetched to {@code path}. */
    public record PlannedMod(Profile.ModEntry entry, String fileName, Path path, Downloader.Artifact download,
                             boolean included, String reason) {
    }

    /** Everything a target gets, plus the enabled entries that contribute nothing to it. */
    public record TargetPlan(Target target, List<PlannedMod> mods, List<String> missingEntries) {
        public List<PlannedMod> included() {
            return mods.stream().filter(PlannedMod::included).toList();
        }

        public List<Path> paths() {
            return included().stream().map(PlannedMod::path).toList();
        }

        public List<Downloader.Artifact> downloads() {
            return included().stream().map(PlannedMod::download).filter(d -> d != null).toList();
        }
    }

    private ModPlanner() {
    }

    public static Map<Target, TargetPlan> plan(Profile profile, List<Target> targets, List<ModResolver.ModFile> local,
                                               ModrinthResolver modrinth, VerityHome home) throws IOException, ProfileException {
        Map<Target, TargetPlan> out = new LinkedHashMap<>();
        for (Target t : targets) out.put(t, planTarget(profile, t, local, modrinth, home));
        return out;
    }

    static TargetPlan planTarget(Profile profile, Target t, List<ModResolver.ModFile> local, ModrinthResolver modrinth,
                                 VerityHome home) throws IOException, ProfileException {
        List<PlannedMod> mods = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> projectsTaken = new HashSet<>();
        for (Profile.ModEntry e : profile.mods) {
            if (e.match == Profile.Match.NEVER) continue;
            boolean contributed = false;
            if (e.fromModrinth()) {
                contributed = planModrinth(e, t, modrinth, home, projectsTaken, mods);
            } else if (e.path != null && !e.path.isBlank()) {
                for (ModResolver.ModFile m : local) {
                    if (m.entry() != e) continue;
                    ModResolver.Decision d = ModResolver.decide(m, t);
                    mods.add(new PlannedMod(e, m.path().getFileName().toString(), m.path(), null, d.included(), d.reason()));
                    contributed |= d.included();
                }
            } else {
                continue;
            }
            if (!contributed) missing.add(e.label());
        }
        return new TargetPlan(t, mods, missing);
    }

    private static boolean planModrinth(Profile.ModEntry e, Target t, ModrinthResolver modrinth, VerityHome home,
                                        Set<String> projectsTaken, List<PlannedMod> mods) throws IOException, ProfileException {
        if (t.loader() == LoaderType.VANILLA) {
            mods.add(new PlannedMod(e, e.modrinth, null, null, false, "vanilla loads no mods"));
            return false;
        }
        String rejected = ModResolver.ruleRejection(e, t);
        if (rejected != null) {
            mods.add(new PlannedMod(e, e.modrinth, null, null, false, rejected));
            return false;
        }
        if (modrinth == null) throw new ProfileException("Modrinth mods need a Modrinth client");
        List<ModrinthResolver.Resolved> resolved = modrinth.resolve(e.modrinth, t, e.channel, e.version, e.dependencies);
        if (resolved.isEmpty()) {
            String what = e.version != null && !e.version.isBlank() ? "version " + e.version + " " : "";
            mods.add(new PlannedMod(e, e.modrinth, null, null, false,
                    "modrinth: no " + what + "file for " + t.loader().id() + " " + t.minecraft()
                            + (e.version == null || e.version.isBlank() ? " (" + (e.channel == null ? "release" : e.channel) + ")" : "")));
            return false;
        }
        for (ModrinthResolver.Resolved r : resolved) {
            if (!projectsTaken.add(r.projectId())) continue; // another entry or dependency already brings it
            String fileName = r.file().filename;
            Path path = home.modsCache().resolve("modrinth").resolve(FileUtil.safeName(r.projectId()))
                    .resolve(FileUtil.safeName(r.version().id)).resolve(FileUtil.safeName(fileName));
            Downloader.Artifact a = new Downloader.Artifact(List.of(URI.create(r.file().url)), path, r.file().sha1(), r.file().size, true);
            String reason = "modrinth " + r.projectSlug() + " " + r.version().versionNumber
                    + (r.dependency() ? " (required by " + r.requiredBy() + ")" : "")
                    + ("release".equalsIgnoreCase(r.version().versionType) ? "" : " [" + r.version().versionType + "]");
            mods.add(new PlannedMod(e, fileName, path, a, true, reason));
        }
        return true;
    }
}

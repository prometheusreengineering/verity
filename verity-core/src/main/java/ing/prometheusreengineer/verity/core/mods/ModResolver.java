package ing.prometheusreengineer.verity.core.mods;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.util.VersionPredicate;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Expands mod entries to jar files and decides which jars each target receives. */
public final class ModResolver {
    /** A jar found for an entry. */
    public record ModFile(Profile.ModEntry entry, ModInfo info) {
        public Path path() {
            return info.file();
        }
    }

    /** Why a jar was or wasn't assigned to a target, for the UI preview and {@code verity plan}. */
    public record Decision(ModFile mod, boolean included, String reason) {
    }

    private ModResolver() {
    }

    public static List<ModFile> expand(Profile profile) throws ProfileException {
        List<ModFile> out = new ArrayList<>();
        Map<Path, Boolean> seen = new LinkedHashMap<>();
        for (Profile.ModEntry entry : profile.mods) {
            if (entry.path == null || entry.path.isBlank() || entry.match == Profile.Match.NEVER) continue;
            for (Path jar : files(profile.baseDir(), entry.path)) {
                if (seen.putIfAbsent(jar, true) != null) continue;
                try {
                    out.add(new ModFile(entry, JarInspector.inspect(jar)));
                } catch (IOException e) {
                    throw new ProfileException("Cannot read mod " + jar + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    static List<Path> files(Path base, String spec) throws ProfileException {
        String normalized = spec.replace('\\', '/');
        boolean glob = normalized.contains("*") || normalized.contains("?") || normalized.contains("{");
        if (!glob) {
            Path p = base.resolve(spec).normalize();
            if (Files.isRegularFile(p)) return List.of(p);
            if (Files.isDirectory(p)) return jarsUnder(p, null);
            throw new ProfileException("Mod path not found: " + p);
        }
        // Split into the fixed directory prefix and the glob part.
        String[] parts = normalized.split("/");
        StringBuilder prefix = new StringBuilder();
        int i = 0;
        for (; i < parts.length; i++) {
            if (parts[i].contains("*") || parts[i].contains("?") || parts[i].contains("{")) break;
            prefix.append(parts[i]).append('/');
        }
        Path root = base.resolve(prefix.length() == 0 ? "." : prefix.toString()).normalize();
        String pattern = String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length));
        if (!Files.isDirectory(root)) throw new ProfileException("Mod folder not found: " + root);
        List<Path> matches = jarsUnder(root, FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        if (matches.isEmpty()) throw new ProfileException("No jars match " + spec + " (in " + root + ")");
        return matches;
    }

    private static List<Path> jarsUnder(Path root, PathMatcher matcher) throws ProfileException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(p -> matcher == null || matcher.matches(Path.of(root.relativize(p).toString().replace('\\', '/'))))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ProfileException("Cannot list " + root + ": " + e.getMessage(), e);
        }
    }

    public static List<Decision> decide(List<ModFile> mods, Target target) {
        List<Decision> out = new ArrayList<>();
        for (ModFile m : mods) out.add(decide(m, target));
        return out;
    }

    public static List<Path> assigned(List<ModFile> mods, Target target) {
        return decide(mods, target).stream().filter(Decision::included).map(d -> d.mod().path()).toList();
    }

    public static Decision decide(ModFile m, Target target) {
        Profile.ModEntry e = m.entry();
        if (target.loader() == LoaderType.VANILLA) return new Decision(m, false, "vanilla loads no mods");
        if (e.hasRules()) {
            String rejected = ruleRejection(e, target);
            return rejected != null ? new Decision(m, false, rejected) : new Decision(m, true, "matches rules");
        }
        if (e.match == Profile.Match.ALWAYS) return new Decision(m, true, "always");
        ModInfo info = m.info();
        if (!info.hasMetadata()) return new Decision(m, true, "no loader metadata (universal)");
        Set<LoaderFamily> accepted = LoaderFamily.acceptedBy(target.loader(), target.minecraft());
        for (LoaderFamily f : accepted) {
            VersionPredicate p = info.constraints().get(f);
            if (p == null) continue;
            if (p.test(target.minecraft())) return new Decision(m, true, metaName(f) + (p.isAny() ? "" : " minecraft " + p));
            return new Decision(m, false, metaName(f) + " requires minecraft " + p);
        }
        if (info.families().size() == 1) {
            return new Decision(m, false, "only " + metaName(info.families().iterator().next()) + " metadata");
        }
        return new Decision(m, true, "multi-loader jar (" + info.families().stream().map(ModResolver::metaName).toList() + ")");
    }

    /** Why an entry's manual rules exclude {@code target}, or null if they allow it (or it has none). */
    public static String ruleRejection(Profile.ModEntry e, Target target) {
        if (e.loaders != null && !e.loaders.isEmpty() && !e.loaders.contains(target.loader())) {
            return "rule: loaders " + e.loaders.stream().map(LoaderType::id).toList();
        }
        if (e.minecraft != null && !e.minecraft.isBlank() && !VersionPredicate.parse(e.minecraft).test(target.minecraft())) {
            return "rule: minecraft " + e.minecraft;
        }
        return null;
    }

    static String metaName(LoaderFamily f) {
        return switch (f) {
            case FABRIC -> "fabric.mod.json";
            case FORGE_LEGACY -> "mcmod.info/tweaker";
            case FORGE_MODERN -> "mods.toml";
            case NEOFORGE -> "neoforge.mods.toml";
        };
    }
}

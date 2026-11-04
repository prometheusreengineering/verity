package dev.verity.core.launch;

import dev.verity.core.install.GameInstaller;
import dev.verity.core.install.InstalledVersion;
import dev.verity.core.install.Libraries;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.util.FileUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out one client's private instance:
 * <pre>
 * instance/
 *   game/      game directory (mods/, config/, options.txt, saves/, logs/, crash-reports/)
 *   natives/   extracted native libraries
 *   home/      sandboxed user home / APPDATA
 * </pre>
 */
public final class InstancePreparer {
    /** Defaults that make unattended clients behave: no pausing when unfocused, no first-run screens, quiet, cheap. */
    static final List<String> DEFAULT_OPTIONS = List.of(
            "pauseOnLostFocus:false",
            "onboardAccessibility:false",
            "skipMultiplayerWarning:true",
            "joinedFirstServer:true",
            "tutorialStep:none",
            "narrator:0",
            "soundCategory_master:0.0",
            "soundCategory_music:0.0",
            "renderDistance:4",
            "maxFps:30",
            "enableVsync:false",
            "fullscreen:false",
            "advancedItemTooltips:false",
            "hideServerAddress:true");

    public record Layout(Path root, Path gameDir, Path nativesDir, Path homeDir) {
        public static Layout of(Path root) {
            return new Layout(root, root.resolve("game"), root.resolve("natives"), root.resolve("home"));
        }
    }

    private InstancePreparer() {
    }

    public static Layout prepare(Path instanceRoot, boolean fresh, Profile profile, List<Path> mods, InstalledVersion version,
                                 List<Libraries.Resolved> libraries, GameInstaller game) throws IOException, ProfileException {
        Layout l = Layout.of(instanceRoot);
        if (fresh) FileUtil.deleteRecursively(instanceRoot);
        Files.createDirectories(l.gameDir());
        Files.createDirectories(l.homeDir());

        // Mods are always replaced so a rebuilt jar is picked up even when the instance is kept.
        Path modsDir = l.gameDir().resolve("mods");
        FileUtil.deleteRecursively(modsDir);
        Files.createDirectories(modsDir);
        for (Path mod : mods) Files.copy(mod, modsDir.resolve(mod.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        for (Profile.FileEntry f : profile.files) {
            if (f.from == null || f.from.isBlank()) continue;
            Path from = profile.baseDir().resolve(f.from).normalize();
            if (!Files.exists(from)) throw new ProfileException("Extra file not found: " + from);
            String to = f.to == null || f.to.isBlank() ? from.getFileName().toString() : f.to;
            Path target = l.gameDir().resolve(to).normalize();
            if (!target.startsWith(l.gameDir())) throw new ProfileException("Extra file target escapes the game directory: " + to);
            FileUtil.copyRecursively(from, target);
        }

        seedOptions(l.gameDir().resolve("options.txt"));

        FileUtil.deleteRecursively(l.nativesDir());
        Natives.extract(libraries, l.nativesDir());

        if (game.isMapToResources(version.version())) {
            FileUtil.copyRecursively(game.virtualAssetsDir(version.version().assetIndex.id), l.gameDir().resolve("resources"));
        }
        return l;
    }

    /** Adds Verity's defaults for keys the user's options.txt does not set. */
    static void seedOptions(Path options) throws IOException {
        List<String> lines = Files.exists(options) ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8)) : new ArrayList<>();
        for (String def : DEFAULT_OPTIONS) {
            String key = def.substring(0, def.indexOf(':') + 1);
            if (lines.stream().noneMatch(l -> l.startsWith(key))) lines.add(def);
        }
        Files.write(options, lines, StandardCharsets.UTF_8);
    }
}

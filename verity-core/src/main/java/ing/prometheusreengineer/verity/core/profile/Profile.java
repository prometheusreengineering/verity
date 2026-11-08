package ing.prometheusreengineer.verity.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ing.prometheusreengineer.verity.core.meta.LoaderType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named test configuration: which Minecraft versions/loaders to run and how every one of them is set up. All
 * settings apply to every target in the profile.
 */
@JsonPropertyOrder({"name", "description", "targets", "mods", "files", "jvm", "game", "display", "account", "test", "run"})
public class Profile {
    public String name;
    public String description;
    public List<TargetSpec> targets = new ArrayList<>();
    public List<ModEntry> mods = new ArrayList<>();
    public List<FileEntry> files = new ArrayList<>();
    public Jvm jvm = new Jvm();
    public Game game = new Game();
    public Display display = new Display();
    public Account account = new Account();
    public Test test = new Test();
    public Run run = new Run();

    /** File this profile was loaded from; relative mod/file paths resolve against its directory. */
    @JsonIgnore
    public Path source;

    @JsonIgnore
    public Path baseDir() {
        if (source != null && source.toAbsolutePath().getParent() != null) return source.toAbsolutePath().getParent();
        return Path.of("").toAbsolutePath();
    }

    /** One explicit target, or a selector that expands to many. */
    public static class TargetSpec {
        public String minecraft;
        public LoaderType loader;
        /** {@code latest}, {@code recommended} (default) or an exact loader version. */
        public String loaderVersion;
        public Selector select;
        public boolean enabled = true;

        public static TargetSpec of(String mc, LoaderType loader) {
            TargetSpec t = new TargetSpec();
            t.minecraft = mc;
            t.loader = loader;
            return t;
        }

        @Override
        public String toString() {
            if (select != null) return "select(" + select + ")";
            return minecraft + "/" + (loader == null ? "vanilla" : loader.id()) + (loaderVersion != null ? "@" + loaderVersion : "");
        }
    }

    public static class Selector {
        /** Version predicate, e.g. {@code >=1.20}, {@code 1.20.x}, {@code [1.8.9,1.12.2]}, {@code latest}. */
        public String minecraft = "*";
        public List<LoaderType> loaders = new ArrayList<>();
        /** Keep only the newest patch release of each minor line (1.20.6, 1.21.10, ...). */
        public boolean latestPatchOnly;
        /** Keep at most this many newest versions per loader (0 = unlimited). */
        public int limit;
        public String loaderVersion;
        /**
         * Modrinth projects (slugs or ids): only (Minecraft version, loader) pairs every listed project has files for.
         */
        public List<String> modrinth = new ArrayList<>();

        @Override
        public String toString() {
            return (modrinth != null && !modrinth.isEmpty() ? "modrinth " + modrinth + " " : "") + minecraft + " x " + loaders
                    + (latestPatchOnly ? " latest-patch" : "") + (limit > 0 ? " limit " + limit : "");
        }
    }

    public enum Match {
        /** Decide from the jar's own metadata (fabric.mod.json, mods.toml, mcmod.info...). */
        AUTO,
        /** Install on every modded target the rules allow. */
        ALWAYS,
        /** Disabled. */
        NEVER;

        @com.fasterxml.jackson.annotation.JsonValue
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Match parse(String s) {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    public static class ModEntry {
        /** File, folder or glob ({@code build/libs/*.jar}, {@code deps/**}). Set this or {@link #modrinth}. */
        public String path;
        /** Modrinth project slug or id; the matching file is picked per target. */
        public String modrinth;
        /** Modrinth: pin a version number or id instead of the newest matching one. */
        public String version;
        /** Modrinth: newest {@code release} (default), or also {@code beta}/{@code alpha} builds. */
        public String channel;
        /** Modrinth: also install the version's required dependencies. */
        public boolean dependencies = true;
        public Match match = Match.AUTO;
        /** Optional manual rule: only these loaders. Setting a rule overrides metadata detection. */
        public List<LoaderType> loaders = new ArrayList<>();
        /** Optional manual rule: only these Minecraft versions. */
        public String minecraft;
        /**
         * Attach the jar with {@code -javaagent} instead of putting it in the mods folder. It goes to every target the
         * rules above allow, vanilla included, since an agent doesn't need a loader. Local paths only.
         */
        public boolean agent;

        public static ModEntry of(String path) {
            ModEntry m = new ModEntry();
            m.path = path;
            return m;
        }

        public static ModEntry modrinth(String project) {
            ModEntry m = new ModEntry();
            m.modrinth = project;
            return m;
        }

        // Not named isModrinth(): Jackson would treat it as the "modrinth" property's getter and drop the field.
        public boolean fromModrinth() {
            return modrinth != null && !modrinth.isBlank();
        }

        /** Short human label: the path or {@code modrinth:slug}. */
        @JsonIgnore
        public String label() {
            return fromModrinth() ? "modrinth:" + modrinth + (version != null && !version.isBlank() ? "@" + version : "") : path;
        }

        @JsonIgnore
        public boolean hasRules() {
            return (loaders != null && !loaders.isEmpty()) || (minecraft != null && !minecraft.isBlank());
        }
    }

    /** Extra file or folder copied into the game directory (configs, options.txt, worlds...). */
    public static class FileEntry {
        public String from;
        public String to;
    }

    public static class Jvm {
        public int memoryMb = 2048;
        public List<String> args = new ArrayList<>();
    }

    public static class Game {
        public List<String> args = new ArrayList<>();
        public Map<String, String> env = new LinkedHashMap<>();
    }

    public enum DisplayMode {
        /** Real rendering on an invisible display (Xvfb on Linux, hidden desktop on Windows). */
        HEADLESS,
        /** Normal visible windows. */
        WINDOW;

        @com.fasterxml.jackson.annotation.JsonValue
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static DisplayMode parse(String s) {
            String n = s.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (n) {
                case "headless", "hidden", "virtual" -> HEADLESS;
                case "window", "windowed", "headful", "visible" -> WINDOW;
                default -> throw new IllegalArgumentException("Unknown display mode '" + s + "' (headless or window)");
            };
        }
    }

    public enum SoftwareGl {
        AUTO, ON, OFF;

        @com.fasterxml.jackson.annotation.JsonValue
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static SoftwareGl parse(String s) {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    public static class Display {
        public DisplayMode mode = DisplayMode.HEADLESS;
        public int width = 854;
        public int height = 480;
        /**
         * Software OpenGL (Mesa llvmpipe). {@code auto} enables it for headless runs on machines without a usable GPU
         * (Xvfb always; Windows when no hardware driver).
         */
        public SoftwareGl softwareGl = SoftwareGl.AUTO;
    }

    public static class Account {
        /** {@code offline} or {@code msa}. */
        public String type = "offline";
        /** Offline player name. */
        public String username = "Verity";
        /** Stored Microsoft account id (see {@code verity account list}). */
        public String id;
    }

    public static class Test {
        /** Seconds to wait for the mod to connect through verity-api. */
        public int startupTimeoutSec = 600;
        /** Hard limit for a whole client run. */
        public int timeoutSec = 900;
        /** Seconds to wait for a graceful exit after {@code finish()} before killing. */
        public int shutdownGraceSec = 10;
        /**
         * Vanilla cannot load mods, so nothing calls verity-api there: a vanilla client passes once it has run this
         * long without crashing (unless something, e.g. a {@code -javaagent}, connects through verity-api). 0 = require
         * the hook on vanilla too.
         */
        public int vanillaSmokeSec = 60;
        /**
         * Run a client once more if it dies of a native fault (an access violation, a fatal signal or a JVM error log)
         * without a Minecraft crash report. CI runners occasionally have such crashes that don't come back; a real bug
         * crashes again. The result records the first attempt in {@code retriedAfter}.
         */
        public boolean retryNativeCrashes = true;
        /**
         * Smoke test for modded targets: a client that has not connected through verity-api passes once it has run
         * this long without crashing. 0 = modded targets must report through verity-api.
         */
        public int smokeSec;
    }

    public static class Run {
        /** Max simultaneous clients; 0 = automatic. */
        public int maxParallel;
        /** Keep instance folders (worlds, configs) between runs instead of starting fresh. */
        public boolean keepInstances;
        /** Point HOME/APPDATA/user.home into the instance so mods cannot touch the real user profile. */
        public boolean isolateHome = true;
        /** Skip (instead of run) targets where some enabled mod entry has no file for that version/loader. */
        public boolean onlyTargetsWithAllMods;
    }
}

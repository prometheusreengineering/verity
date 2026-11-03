package dev.verity.core.home;

import dev.verity.core.util.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verity's private data directory. Everything Verity downloads or creates lives here; the user's own
 * {@code .minecraft} is never read or written.
 */
public final class VerityHome {
    public static final String ENV = "VERITY_HOME";

    private final Path root;

    public VerityHome(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** {@code override} (CLI {@code --home}) → {@code $VERITY_HOME} → per-OS default. */
    public static VerityHome resolve(String override) {
        if (override != null && !override.isBlank()) return new VerityHome(Path.of(override));
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return new VerityHome(Path.of(env));
        return new VerityHome(defaultRoot());
    }

    public static Path defaultRoot() {
        String userHome = System.getProperty("user.home");
        return switch (Os.current()) {
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                yield local != null ? Path.of(local, "Verity") : Path.of(userHome, "AppData", "Local", "Verity");
            }
            case OSX -> Path.of(userHome, "Library", "Application Support", "Verity");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield xdg != null && !xdg.isBlank() ? Path.of(xdg, "verity") : Path.of(userHome, ".local", "share", "verity");
            }
        };
    }

    public Path root() {
        return root;
    }

    public Path libraries() {
        return root.resolve("libraries");
    }

    public Path assets() {
        return root.resolve("assets");
    }

    public Path versions() {
        return root.resolve("versions");
    }

    public Path runtimes() {
        return root.resolve("runtimes");
    }

    public Path installers() {
        return root.resolve("installers");
    }

    public Path metaCache() {
        return root.resolve("meta-cache");
    }

    public Path profiles() {
        return root.resolve("profiles");
    }

    public Path accounts() {
        return root.resolve("accounts");
    }

    public Path runs() {
        return root.resolve("runs");
    }

    public Path instances() {
        return root.resolve("instances");
    }

    /** Mod jars downloaded from Modrinth, content-addressed by project and version. */
    public Path modsCache() {
        return root.resolve("mods-cache");
    }

    public Path tools() {
        return root.resolve("tools");
    }

    public Path locks() {
        return root.resolve("locks");
    }

    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    public VerityHome ensureCreated() throws IOException {
        for (Path p : new Path[] {libraries(), assets(), versions(), runtimes(), installers(), metaCache(), profiles(),
                accounts(), runs(), instances(), tools(), locks()}) {
            Files.createDirectories(p);
        }
        return this;
    }

    @Override
    public String toString() {
        return root.toString();
    }
}

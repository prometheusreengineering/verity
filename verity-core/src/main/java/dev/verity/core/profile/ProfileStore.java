package dev.verity.core.profile;

import dev.verity.core.home.VerityHome;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Profiles saved under {@code <home>/profiles/<name>.json}, plus loading from arbitrary files. */
public final class ProfileStore {
    private final Path dir;

    public ProfileStore(VerityHome home) {
        this.dir = home.profiles();
    }

    public Path dir() {
        return dir;
    }

    public List<Profile> list() throws IOException {
        List<Profile> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                try {
                    out.add(load(p));
                } catch (IOException e) {
                    System.err.println("Skipping unreadable profile " + p + ": " + e.getMessage());
                }
            }
        }
        out.sort(Comparator.comparing(p -> p.name.toLowerCase()));
        return out;
    }

    public Path pathFor(String name) {
        return dir.resolve(FileUtil.safeName(name) + ".json");
    }

    public Optional<Profile> find(String name) throws IOException {
        Path p = pathFor(name);
        if (Files.isRegularFile(p)) return Optional.of(load(p));
        for (Profile profile : list()) if (profile.name.equalsIgnoreCase(name)) return Optional.of(profile);
        return Optional.empty();
    }

    /** Resolves a CLI argument: an existing file path, else a stored profile name. */
    public Profile resolve(String nameOrPath) throws IOException, ProfileException {
        Path asPath = Path.of(nameOrPath);
        if (Files.isRegularFile(asPath)) return load(asPath);
        return find(nameOrPath).orElseThrow(() -> new ProfileException(
                "No profile named '" + nameOrPath + "' and no such file (profiles live in " + dir + ")"));
    }

    public static Profile load(Path file) throws IOException {
        Profile p = Json.read(file, Profile.class);
        p.source = file.toAbsolutePath().normalize();
        if (p.name == null || p.name.isBlank()) {
            String fn = file.getFileName().toString();
            p.name = fn.endsWith(".json") ? fn.substring(0, fn.length() - 5) : fn;
        }
        return p;
    }

    /** Saves into the store as {@code <name>.json}; returns the file written. */
    public Path save(Profile p) throws IOException {
        Path target = pathFor(p.name);
        Json.write(target, p);
        p.source = target;
        return target;
    }

    /** Renames a stored profile (moves its file). */
    public void rename(Profile p, String newName) throws IOException {
        Path old = p.source;
        p.name = newName;
        Path target = save(p);
        if (old != null && old.startsWith(dir) && !old.equals(target)) Files.deleteIfExists(old);
    }

    /**
     * Copies a profile file into the store. Relative mod/file paths are made absolute first so they keep pointing
     * at the same files.
     */
    public Profile importProfile(Path file) throws IOException {
        Profile p = load(file);
        Path base = p.baseDir();
        for (Profile.ModEntry m : p.mods) if (m.path != null) m.path = absolutize(base, m.path);
        for (Profile.FileEntry f : p.files) if (f.from != null) f.from = absolutize(base, f.from);
        p.source = null;
        save(p);
        return p;
    }

    /** Absolute form of a path that may contain glob characters (which {@link Path} rejects on Windows). */
    public static String absolutize(Path base, String spec) {
        if (isAbsolute(spec)) return spec;
        boolean glob = spec.contains("*") || spec.contains("?") || spec.contains("{");
        if (!glob) return base.resolve(spec).normalize().toString();
        String b = base.toString().replace('\\', '/');
        return (b.endsWith("/") ? b : b + "/") + spec.replace('\\', '/');
    }

    public static boolean isAbsolute(String spec) {
        return spec.startsWith("/") || spec.startsWith("\\") || spec.matches("^[A-Za-z]:[\\\\/].*");
    }

    public static void saveTo(Profile p, Path file) throws IOException {
        Json.write(file, p);
    }

    public boolean delete(Profile p) throws IOException {
        Path target = p.source != null && p.source.startsWith(dir) && p.source.getFileName().equals(pathFor(p.name).getFileName())
                ? p.source : pathFor(p.name);
        return Files.deleteIfExists(target);
    }

    /** Deep copy via JSON, keeping the source directory for relative paths. */
    public static Profile copy(Profile p) {
        try {
            Profile c = Json.MAPPER.readValue(Json.MAPPER.writeValueAsBytes(p), Profile.class);
            c.source = p.source;
            return c;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

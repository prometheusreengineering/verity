package dev.verity.core.mods;

import dev.verity.core.util.VersionPredicate;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * What a jar declares about itself.
 *
 * @param constraints Minecraft version requirement per family; families present without a requirement map to
 *                    {@link VersionPredicate#ANY}
 */
public record ModInfo(Path file, String id, String name, String version, Map<LoaderFamily, VersionPredicate> constraints) {
    public Set<LoaderFamily> families() {
        return constraints.keySet();
    }

    public boolean hasMetadata() {
        return !constraints.isEmpty();
    }

    public String displayName() {
        return name != null ? name : (id != null ? id : file.getFileName().toString());
    }
}

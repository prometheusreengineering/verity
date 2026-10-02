package dev.verity.core.install;

import dev.verity.core.meta.VersionJson;
import java.nio.file.Path;

/** A fully installed, flattened version ready for {@code LaunchCommandBuilder}. */
public record InstalledVersion(String id, VersionJson version, Path clientJar) {
    /** Java runtime component Mojang recommends; pre-1.17 versions without the field run on Java 8. */
    public String javaComponent() {
        return version.javaVersion != null && version.javaVersion.component != null ? version.javaVersion.component : "jre-legacy";
    }

    public int javaMajor() {
        return version.javaVersion != null && version.javaVersion.majorVersion > 0 ? version.javaVersion.majorVersion : 8;
    }
}

package dev.verity.core.meta;

import java.io.IOException;
import java.util.List;

/** Lists the versions a mod loader offers. */
public interface LoaderMeta {
    LoaderType type();

    /** Minecraft release ids this loader supports, any order. */
    List<String> supportedGameVersions() throws IOException;

    /** Loader versions available for {@code mc}, newest first. */
    List<String> loaderVersions(String mc) throws IOException;

    /**
     * Resolves a user spec ({@code null}/{@code latest}/{@code recommended}/{@code stable} or an explicit version) to a
     * concrete loader version for {@code mc}.
     */
    String resolve(String mc, String spec) throws IOException;

    static boolean isAlias(String spec) {
        return spec == null || spec.isBlank() || spec.equalsIgnoreCase("latest") || spec.equalsIgnoreCase("recommended")
                || spec.equalsIgnoreCase("stable");
    }
}

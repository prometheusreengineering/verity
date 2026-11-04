package dev.verity.core.run;

import dev.verity.core.meta.LoaderType;

/**
 * One concrete client to run.
 *
 * @param loaderVersion requested loader version spec ({@code null}/latest/recommended or exact); resolved at prepare time
 */
public record Target(String minecraft, LoaderType loader, String loaderVersion) {
    /** Stable id used for folders and reports, e.g. {@code 1.20.1-fabric}. */
    public String id() {
        String base = minecraft + "-" + loader.id();
        boolean pinned = loaderVersion != null && !loaderVersion.isBlank() && !loaderVersion.equalsIgnoreCase("latest")
                && !loaderVersion.equalsIgnoreCase("recommended") && !loaderVersion.equalsIgnoreCase("stable");
        return pinned ? base + "-" + loaderVersion.replaceAll("[^A-Za-z0-9._+-]", "_") : base;
    }

    public String displayName() {
        return loader.displayName() + " " + minecraft + (loaderVersion != null && !loaderVersion.isBlank() ? " (" + loaderVersion + ")" : "");
    }

    /** Matches CLI filters like {@code 1.20.1}, {@code fabric}, {@code 1.20.1-fabric} or {@code 1.20.1/fabric}. */
    public boolean matchesFilter(String filter) {
        String f = filter.trim().toLowerCase().replace('/', '-');
        if (f.isEmpty()) return true;
        if (id().equalsIgnoreCase(f) || id().startsWith(f + "-")) return true;
        if (f.equals(loader.id()) || f.equals(minecraft)) return true;
        return (minecraft + "-" + loader.id()).equals(f);
    }

    @Override
    public String toString() {
        return id();
    }
}

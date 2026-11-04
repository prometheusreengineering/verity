package dev.verity.core.mods;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.util.McVersion;
import java.util.EnumSet;
import java.util.Set;

/** Mod metadata families; one loader/version can accept several. */
public enum LoaderFamily {
    /** {@code fabric.mod.json}. */
    FABRIC,
    /** LaunchWrapper-era Forge: {@code mcmod.info}, {@code TweakClass}/{@code FMLCorePlugin} manifests. */
    FORGE_LEGACY,
    /** {@code META-INF/mods.toml} for MinecraftForge 1.13+. */
    FORGE_MODERN,
    /** {@code META-INF/neoforge.mods.toml}, or {@code mods.toml} depending on {@code neoforge}. */
    NEOFORGE;

    /** Families whose metadata a (loader, Minecraft version) target reads. */
    public static Set<LoaderFamily> acceptedBy(LoaderType loader, String mc) {
        return switch (loader) {
            case VANILLA -> EnumSet.noneOf(LoaderFamily.class);
            case FABRIC -> EnumSet.of(FABRIC);
            case FORGE -> McVersion.compare(mc, "1.13") < 0 ? EnumSet.of(FORGE_LEGACY) : EnumSet.of(FORGE_MODERN);
            // NeoForge for 1.20.1 is a Forge fork and still loads Forge mods.
            case NEOFORGE -> mc.equals("1.20.1") ? EnumSet.of(NEOFORGE, FORGE_MODERN) : EnumSet.of(NEOFORGE);
        };
    }
}

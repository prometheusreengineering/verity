package ing.prometheusreengineer.verity.core.install;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.util.Progress;

/** Installs any (Minecraft version, loader, loader version) combination into the Verity home. */
public final class Installer {
    private final InstallContext ctx;
    private final GameInstaller game;
    private final VanillaInstaller vanilla;
    private final FabricInstaller fabric;
    private final ForgeInstaller forge;

    public Installer(InstallContext ctx) {
        this.ctx = ctx;
        this.game = new GameInstaller(ctx);
        this.vanilla = new VanillaInstaller(ctx, game);
        this.fabric = new FabricInstaller(ctx, game);
        this.forge = new ForgeInstaller(ctx, game);
    }

    public GameInstaller game() {
        return game;
    }

    public InstallContext context() {
        return ctx;
    }

    /** @param loaderVersion concrete loader version (already resolved); ignored for vanilla */
    public InstalledVersion install(String mc, LoaderType loader, String loaderVersion, Progress progress) throws Exception {
        return switch (loader) {
            case VANILLA -> vanilla.install(mc, progress);
            case FABRIC -> fabric.install(mc, loaderVersion, progress);
            case FORGE, NEOFORGE -> forge.install(loader, mc, loaderVersion, progress);
        };
    }
}

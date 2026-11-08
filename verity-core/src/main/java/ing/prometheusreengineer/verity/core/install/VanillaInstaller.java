package ing.prometheusreengineer.verity.core.install;

import ing.prometheusreengineer.verity.core.util.Progress;

public final class VanillaInstaller {
    private final InstallContext ctx;
    private final GameInstaller game;

    public VanillaInstaller(InstallContext ctx, GameInstaller game) {
        this.ctx = ctx;
        this.game = game;
    }

    public InstalledVersion install(String mc, Progress progress) throws Exception {
        return ctx.locks().withLock("install-" + mc, () -> {
            game.ensureVanilla(mc, progress);
            return game.complete(mc, progress);
        });
    }
}

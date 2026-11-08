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
            // For agents on vanilla, which only sees obfuscated names (passed to the game as verity.mappings).
            game.ensureClientMappings(mc);
            return game.complete(mc, progress);
        });
    }
}

package ing.prometheusreengineer.verity.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import ing.prometheusreengineer.verity.core.util.Json;
import ing.prometheusreengineer.verity.core.util.Progress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs a Fabric loader profile ({@code fabric-loader-<loader>-<mc>}) on top of vanilla. */
public final class FabricInstaller {
    private final InstallContext ctx;
    private final GameInstaller game;

    public FabricInstaller(InstallContext ctx, GameInstaller game) {
        this.ctx = ctx;
        this.game = game;
    }

    public static String versionId(String mc, String loader) {
        return "fabric-loader-" + loader + "-" + mc;
    }

    public InstalledVersion install(String mc, String loader, Progress progress) throws Exception {
        String id = versionId(mc, loader);
        return ctx.locks().withLock("install-" + id, () -> {
            game.ensureVanilla(mc, progress);
            Path json = ctx.resolver().jsonPath(id);
            if (!Files.isRegularFile(json)) {
                progress.stage("Fetching Fabric loader " + loader);
                String body = ctx.cache().getImmutable(ctx.meta().fabric().profileUrl(mc, loader));
                JsonNode node = Json.tree(body);
                if (!id.equals(node.path("id").asText())) {
                    throw new IOException("Unexpected Fabric profile id " + node.path("id").asText() + " (wanted " + id + ")");
                }
                FileUtil.writeAtomically(json, body.getBytes(StandardCharsets.UTF_8));
            }
            return game.complete(id, progress);
        });
    }
}

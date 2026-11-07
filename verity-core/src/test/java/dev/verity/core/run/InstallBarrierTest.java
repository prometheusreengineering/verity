package dev.verity.core.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verity.core.meta.LoaderType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InstallBarrierTest {
    static final Target FORGE = new Target("1.20.1", LoaderType.FORGE, null);
    static final Target NEOFORGE = new Target("1.20.1", LoaderType.NEOFORGE, null);
    static final Target FABRIC = new Target("1.20.1", LoaderType.FABRIC, null);
    static final Target OTHER = new Target("1.21.1", LoaderType.FORGE, null);

    @Test
    void forgeClientsWaitForEveryForgeInstallOfTheirVersion() throws Exception {
        InstallBarrier b = new InstallBarrier(List.of(FORGE, NEOFORGE, FABRIC, OTHER));
        assertThat(b.shared(FABRIC)).isFalse();
        b.installed(FORGE);
        CompletableFuture<Void> forgeLaunch = CompletableFuture.runAsync(() -> {
            try {
                b.await(FORGE);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertThat(forgeLaunch).isNotDone();
        b.installed(OTHER); // another version's install doesn't count
        Thread.sleep(100);
        assertThat(forgeLaunch).isNotDone();
        b.installed(NEOFORGE);
        forgeLaunch.get(5, TimeUnit.SECONDS);
        assertThat(b.shared(FORGE)).isFalse();
    }
}

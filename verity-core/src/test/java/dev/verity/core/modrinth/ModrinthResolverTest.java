package dev.verity.core.modrinth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.net.Http;
import dev.verity.core.net.MetaCache;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.run.Target;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModrinthResolverTest {
    @TempDir
    Path tmp;

    FakeModrinth fake;
    ModrinthResolver resolver;

    @BeforeEach
    void start() throws Exception {
        fake = FakeModrinth.essential(tmp.resolve("files"));
        Http http = new Http(HttpClient.newBuilder().proxy(ProxySelector.of(null)).build());
        resolver = new ModrinthResolver(new ModrinthClient(new MetaCache(http, tmp.resolve("cache"), Duration.ofMinutes(5)), fake.base));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    static List<String> files(List<ModrinthResolver.Resolved> r) {
        return r.stream().map(x -> x.file().filename).toList();
    }

    @Test
    void picksNewestFileForTargetWithinChannelAndAddsDependencies() throws Exception {
        List<ModrinthResolver.Resolved> fabric = resolver.resolve("essential", new Target("1.20.1", LoaderType.FABRIC, null), null, null, true);
        assertThat(files(fabric)).containsExactly("essential-fabric-1.20.1.jar", "fabric-api-1.20.1.jar");
        assertThat(fabric.get(1).dependency()).isTrue();
        assertThat(fabric.get(1).requiredBy()).isEqualTo("essential");

        assertThat(files(resolver.resolve("essential", new Target("1.20.1", LoaderType.FABRIC, null), "beta", null, false)))
                .containsExactly("essential-fabric-1.20.1-beta.jar");
        assertThat(files(resolver.resolve("ESS", new Target("1.8.9", LoaderType.FORGE, null), null, null, true)))
                .containsExactly("essential-forge-1.8.9.jar");
        assertThat(resolver.resolve("essential", new Target("1.12.2", LoaderType.FORGE, null), null, null, true)).isEmpty();
        // alpha-only build is ignored on the default channel, used on alpha
        assertThat(resolver.resolve("essential", new Target("1.21.1", LoaderType.FABRIC, null), null, null, true)).isEmpty();
        assertThat(files(resolver.resolve("essential", new Target("1.21.1", LoaderType.FABRIC, null), "alpha", null, true)))
                .containsExactly("essential-fabric-1.21.1-alpha.jar");
        // NeoForge 1.20.1 falls back to Forge builds
        assertThat(files(resolver.resolve("essential", new Target("1.20.1", LoaderType.NEOFORGE, null), null, null, true)))
                .containsExactly("essential-forge-1.20.1.jar");
        // pins match the version number or id, regardless of channel
        assertThat(files(resolver.resolve("essential", new Target("1.20.1", LoaderType.FABRIC, null), null, "1.3.1-beta+fabric-1.20.1", false)))
                .containsExactly("essential-fabric-1.20.1-beta.jar");
        assertThat(resolver.resolve("essential", new Target("1.20.1", LoaderType.FABRIC, null), null, "v-forge-189", false)).isEmpty();
        assertThat(resolver.resolve("essential", new Target("1.20.1", LoaderType.VANILLA, null), null, null, true)).isEmpty();
    }

    @Test
    void supportedTargetsAreReleasePairsInChannel() throws Exception {
        assertThat(resolver.supportedTargets("essential", "release")).extracting(Target::id)
                .containsExactlyInAnyOrder("1.20.1-fabric", "1.8.9-forge", "1.20.1-forge", "1.21.1-neoforge");
        assertThat(resolver.supportedTargets("essential", "alpha")).extracting(Target::id)
                .contains("1.21.1-fabric").doesNotContain("24w14a-fabric");
    }

    @Test
    void unknownProjectIsConfigError() {
        assertThatThrownBy(() -> resolver.resolve("nope", new Target("1.20.1", LoaderType.FABRIC, null), null, null, true))
                .isInstanceOf(ProfileException.class).hasMessageContaining("nope");
    }

    @Test
    void searchAndVersions() throws Exception {
        assertThat(resolver.client().search("essen", 5)).extracting(h -> h.slug).containsExactly("essential");
        assertThat(resolver.client().versions("essential").get(0).versionNumber).isEqualTo("1.4.0-alpha+fabric-1.21.1");
    }
}

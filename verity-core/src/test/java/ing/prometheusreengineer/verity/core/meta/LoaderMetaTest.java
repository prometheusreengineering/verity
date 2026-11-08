package ing.prometheusreengineer.verity.core.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoaderMetaTest {
    @Test
    void forgeGroupingAndShortVersions() {
        Map<String, List<String>> g = ForgeMeta.group(List.of(
                "1.7.10-10.13.4.1614-1.7.10", "1.8.9-11.15.1.2318-1.8.9", "1.20.1-47.2.0", "1.20.1-47.3.0"));
        assertThat(g.get("1.20.1")).containsExactly("1.20.1-47.3.0", "1.20.1-47.2.0");
        assertThat(ForgeMeta.shortVersion("1.8.9", "1.8.9-11.15.1.2318-1.8.9")).isEqualTo("11.15.1.2318");
        assertThat(ForgeMeta.shortVersion("1.20.1", "1.20.1-47.3.0")).isEqualTo("47.3.0");
        assertThat(ForgeMeta.installerUrl("1.20.1-47.3.0"))
                .isEqualTo("https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar");
    }

    @Test
    void neoforgeGameVersions() {
        assertThat(NeoForgeMeta.gameVersionOf("20.2.86")).isEqualTo("1.20.2");
        assertThat(NeoForgeMeta.gameVersionOf("20.4.237")).isEqualTo("1.20.4");
        assertThat(NeoForgeMeta.gameVersionOf("21.0.167")).isEqualTo("1.21");
        assertThat(NeoForgeMeta.gameVersionOf("21.1.77")).isEqualTo("1.21.1");
        assertThat(NeoForgeMeta.gameVersionOf("21.0.0-beta")).isEqualTo("1.21");
        assertThat(NeoForgeMeta.gameVersionOf("26.1.0.5-beta")).isEqualTo("26.1");
        assertThat(NeoForgeMeta.gameVersionOf("26.1.2.10")).isEqualTo("26.1.2");
        assertThat(NeoForgeMeta.installerUrl("1.20.1-47.1.106")).endsWith("net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar");
        assertThat(NeoForgeMeta.installerUrl("21.1.77")).endsWith("net/neoforged/neoforge/21.1.77/neoforge-21.1.77-installer.jar");
    }

    @Test
    void mavenMetadata() {
        assertThat(MavenMetadata.versions("<metadata><versioning><versions><version>1</version>\n<version> 2 </version></versions></versioning></metadata>"))
                .containsExactly("1", "2");
    }

    @Test
    void loaderTypeParsing() {
        assertThat(LoaderType.parse("NeoForge")).isEqualTo(LoaderType.NEOFORGE);
        assertThat(LoaderType.parse("neo-forge")).isEqualTo(LoaderType.NEOFORGE);
        assertThat(LoaderType.parse("vanilla")).isEqualTo(LoaderType.VANILLA);
    }
}

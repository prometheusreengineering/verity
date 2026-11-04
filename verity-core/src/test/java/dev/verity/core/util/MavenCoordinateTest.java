package dev.verity.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenCoordinateTest {
    @Test
    void parsesAndBuildsPaths() {
        MavenCoordinate c = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.1:natives-linux");
        assertThat(c.path()).isEqualTo("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar");
        assertThat(c.versionlessKey()).isEqualTo("org.lwjgl:lwjgl:natives-linux");

        MavenCoordinate d = MavenCoordinate.parse("[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip]");
        assertThat(d.extension()).isEqualTo("zip");
        assertThat(d.path()).isEqualTo("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip");

        MavenCoordinate e = MavenCoordinate.parse("net.minecraft:client:1.20.1-20230612.114412:mappings@txt");
        assertThat(e.fileName()).isEqualTo("client-1.20.1-20230612.114412-mappings.txt");
        assertThat(e.toString()).isEqualTo("net.minecraft:client:1.20.1-20230612.114412:mappings@txt");
    }
}

package dev.verity.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verity.core.TestVersions;
import dev.verity.core.auth.LaunchAccount;
import dev.verity.core.install.InstalledVersion;
import dev.verity.core.install.VersionResolver;
import dev.verity.core.meta.Rule;
import dev.verity.core.util.Os;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchCommandBuilderTest {
    @TempDir
    Path tmp;

    static final Rule.Environment LINUX = new Rule.Environment(Os.LINUX, "x86_64", "6.0", Map.of());

    LaunchRequest request(String id) throws Exception {
        VersionResolver r = new VersionResolver(TestVersions.install(tmp.resolve("versions"), "1.8.9",
                "1.8.9-forge1.8.9-11.15.1.2318-1.8.9", "1.20.1", "fabric-loader-0.16.14-1.20.1"));
        InstalledVersion v = new InstalledVersion(id, r.resolve(id), tmp.resolve("client.jar"));
        return new LaunchRequest(v, Path.of("/java/bin/java"), tmp.resolve("game"), tmp.resolve("natives"), tmp.resolve("libraries"),
                tmp.resolve("assets"), tmp.resolve("assets"), tmp.resolve("log.xml"), List.of(Path.of("/a.jar"), Path.of("/b.jar")),
                LaunchAccount.offline("Verity"), 2048, 854, 480, List.of("-Dextra=1"), List.of("--extraGame"),
                Map.of("verity.port", "1234"));
    }

    @Test
    void modernFabric() throws Exception {
        List<String> cmd = LaunchCommandBuilder.build(request("fabric-loader-0.16.14-1.20.1"), LINUX);
        assertThat(cmd.get(0)).isEqualTo(Path.of("/java/bin/java").toString());
        assertThat(cmd).contains("-Xmx2048M", "-Dverity.port=1234", "-cp", Path.of("/a.jar") + ":" + Path.of("/b.jar"), "-Dextra=1",
                "-DFabricMcEmu= net.minecraft.client.main.Main ", "--width", "854", "--extraGame",
                "-Djava.library.path=" + tmp.resolve("natives"), "-Dlog4j.configurationFile=" + tmp.resolve("log.xml"));
        assertThat(cmd).doesNotContain("--demo", "-XstartOnFirstThread", "--quickPlayPath");
        int main = cmd.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient");
        assertThat(main).isGreaterThan(cmd.indexOf("-cp"));
        assertThat(cmd.get(cmd.indexOf("--username") + 1)).isEqualTo("Verity");
        assertThat(cmd.get(cmd.indexOf("--version") + 1)).isEqualTo("fabric-loader-0.16.14-1.20.1");
        assertThat(cmd.get(cmd.indexOf("--userType") + 1)).isEqualTo("legacy");
        assertThat(cmd.get(cmd.indexOf("--uuid") + 1)).doesNotContain("-").hasSize(32);
        assertThat(String.join(" ", cmd)).doesNotContain("${");
    }

    @Test
    void legacyForge() throws Exception {
        List<String> cmd = LaunchCommandBuilder.build(request("1.8.9-forge1.8.9-11.15.1.2318-1.8.9"), LINUX);
        int main = cmd.indexOf("net.minecraft.launchwrapper.Launch");
        assertThat(main).isGreaterThan(0);
        assertThat(cmd.subList(0, main)).contains("-cp", "-Djava.library.path=" + tmp.resolve("natives"));
        assertThat(cmd.subList(main, cmd.size())).contains("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker",
                "--width", "854", "--height", "480");
        assertThat(cmd.get(cmd.indexOf("--assetIndex") + 1)).isEqualTo("1.8");
        assertThat(String.join(" ", cmd)).doesNotContain("${");
    }
}

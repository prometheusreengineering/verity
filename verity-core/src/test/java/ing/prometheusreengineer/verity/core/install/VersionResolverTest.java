package ing.prometheusreengineer.verity.core.install;

import static org.assertj.core.api.Assertions.assertThat;

import ing.prometheusreengineer.verity.core.TestVersions;
import ing.prometheusreengineer.verity.core.meta.Rule;
import ing.prometheusreengineer.verity.core.meta.VersionJson;
import ing.prometheusreengineer.verity.core.util.Os;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionResolverTest {
    @TempDir
    Path tmp;

    static final Rule.Environment LINUX = new Rule.Environment(Os.LINUX, "x86_64", "6.0", Map.of());
    static final Rule.Environment WINDOWS = new Rule.Environment(Os.WINDOWS, "x86_64", "10.0", Map.of());

    @Test
    void mergesFabricOverVanilla() throws Exception {
        VersionResolver r = new VersionResolver(TestVersions.install(tmp, "1.20.1", "fabric-loader-0.16.14-1.20.1"));
        List<VersionJson> chain = r.chain("fabric-loader-0.16.14-1.20.1");
        assertThat(chain).extracting(v -> v.id).containsExactly("fabric-loader-0.16.14-1.20.1", "1.20.1");
        assertThat(VersionResolver.baseJarId(chain)).isEqualTo("1.20.1");

        VersionJson m = VersionResolver.merge(chain);
        assertThat(m.id).isEqualTo("fabric-loader-0.16.14-1.20.1");
        assertThat(m.mainClass).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotClient");
        assertThat(m.javaVersion.majorVersion).isEqualTo(17);
        assertThat(m.assetIndex.id).isEqualTo("5");
        // Fabric's newer asm wins over vanilla's
        assertThat(m.libraries).extracting(l -> l.name).contains("org.ow2.asm:asm:9.8").doesNotContain("org.ow2.asm:asm:9.3");
        // vanilla jvm args come before Fabric's
        assertThat(m.arguments.jvm.get(m.arguments.jvm.size() - 1).values()).containsExactly("-DFabricMcEmu= net.minecraft.client.main.Main ");
    }

    @Test
    void resolvesLibrariesPerOs() throws Exception {
        VersionResolver r = new VersionResolver(TestVersions.install(tmp, "1.20.1", "fabric-loader-0.16.14-1.20.1"));
        VersionJson m = r.resolve("fabric-loader-0.16.14-1.20.1");
        Path libs = tmp.resolve("libraries");

        List<Libraries.Resolved> linux = Libraries.resolve(m, LINUX, libs);
        assertThat(linux).extracting(x -> x.coordinate().toString())
                .contains("org.lwjgl:lwjgl:3.3.1:natives-linux").doesNotContain("org.lwjgl:lwjgl:3.3.1:natives-windows");
        Libraries.Resolved fabric = linux.stream().filter(x -> x.coordinate().artifact().equals("fabric-loader")).findFirst().orElseThrow();
        assertThat(fabric.urls().get(0).toString()).isEqualTo("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.14/fabric-loader-0.16.14.jar");
        assertThat(fabric.path()).isEqualTo(libs.resolve("net/fabricmc/fabric-loader/0.16.14/fabric-loader-0.16.14.jar"));
        Libraries.Resolved asm = linux.stream().filter(x -> x.coordinate().artifact().equals("asm")).findFirst().orElseThrow();
        assertThat(asm.sha1()).isEqualTo("dc19ecb3f7889b7860697215cae99c0f9b6f6b4b");
        assertThat(asm.size()).isEqualTo(126113);
    }

    @Test
    void keepsPerOsDuplicatesWithinOneVersionButLetsChildrenOverride() throws Exception {
        // 1.14-1.18 shape: the same library twice in one JSON, picked by rules (macOS 3.2.1, everything else 3.2.2).
        VersionJson vanilla = ing.prometheusreengineer.verity.core.util.Json.MAPPER.readValue("""
                {"id":"1.16.5","libraries":[
                  {"name":"org.lwjgl:lwjgl-glfw:3.2.1","rules":[{"action":"allow","os":{"name":"osx"}}]},
                  {"name":"org.lwjgl:lwjgl-glfw:3.2.2","rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]},
                  {"name":"org.ow2.asm:asm:9.0"}]}
                """, VersionJson.class);
        VersionJson loader = ing.prometheusreengineer.verity.core.util.Json.MAPPER.readValue("""
                {"id":"loader","inheritsFrom":"1.16.5","libraries":[{"name":"org.ow2.asm:asm:9.8"}]}
                """, VersionJson.class);
        VersionJson m = VersionResolver.merge(List.of(loader, vanilla));
        assertThat(m.libraries).extracting(l -> l.name)
                .containsExactly("org.ow2.asm:asm:9.8", "org.lwjgl:lwjgl-glfw:3.2.1", "org.lwjgl:lwjgl-glfw:3.2.2");
        assertThat(Libraries.resolve(m, WINDOWS, tmp)).extracting(x -> x.coordinate().toString())
                .contains("org.lwjgl:lwjgl-glfw:3.2.2").doesNotContain("org.lwjgl:lwjgl-glfw:3.2.1");
    }

    @Test
    void legacyForgeAndNatives() throws Exception {
        VersionResolver r = new VersionResolver(TestVersions.install(tmp, "1.8.9", "1.8.9-forge1.8.9-11.15.1.2318-1.8.9"));
        VersionJson m = r.resolve("1.8.9-forge1.8.9-11.15.1.2318-1.8.9");
        assertThat(m.mainClass).isEqualTo("net.minecraft.launchwrapper.Launch");
        assertThat(m.minecraftArguments).contains("FMLTweaker");
        assertThat(m.arguments).isNull();

        List<Libraries.Resolved> linux = Libraries.resolve(m, LINUX, tmp.resolve("libraries"));
        assertThat(linux).extracting(x -> x.coordinate().artifact()).doesNotContain("lzma", "twitch-platform");
        Libraries.Resolved natives = linux.stream().filter(Libraries.Resolved::natives).findFirst().orElseThrow();
        assertThat(natives.coordinate().classifier()).isEqualTo("natives-linux");
        assertThat(natives.extractExcludes()).containsExactly("META-INF/");
        Libraries.Resolved forge = linux.stream().filter(x -> x.coordinate().artifact().equals("forge")).findFirst().orElseThrow();
        assertThat(forge.urls().get(0).toString()).startsWith("https://maven.minecraftforge.net/net/minecraftforge/forge/");
        Libraries.Resolved lw = linux.stream().filter(x -> x.coordinate().artifact().equals("launchwrapper")).findFirst().orElseThrow();
        assertThat(lw.urls().get(0).toString()).isEqualTo("https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar");
        assertThat(lw.urls()).hasSizeGreaterThan(2);

        List<Libraries.Resolved> win = Libraries.resolve(m, WINDOWS, tmp.resolve("libraries"));
        assertThat(win).filteredOn(Libraries.Resolved::natives).extracting(x -> x.coordinate().classifier())
                .contains("natives-windows", "natives-windows-64");
    }
}

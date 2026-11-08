package ing.prometheusreengineer.verity.core.mods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ing.prometheusreengineer.verity.core.TestJars;
import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.run.Target;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModResolverTest {
    @TempDir
    Path tmp;

    Path universal;

    @BeforeEach
    void jars() throws Exception {
        // Like minecraft-essential: one jar with Fabric metadata + LaunchWrapper tweaker + mcmod.info
        universal = TestJars.jar(tmp.resolve("build/libs/prometheus-1.6.1.jar"), Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"prometheus\",\"version\":\"1.6.1\",\"depends\":{\"minecraft\":\">=1.16\"}}",
                "mcmod.info", "[{\"modid\":\"prometheus\",\"name\":\"Prometheus\",\"mcversion\":\"${mcversion}\"}]"),
                Map.of("TweakClass", "org.spongepowered.asm.launch.MixinTweaker"));
        TestJars.jar(tmp.resolve("deps/essential-fabric-1.20.1.jar"), Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"essential\",\"depends\":{\"minecraft\":\"~1.20.1\"}}"));
        TestJars.jar(tmp.resolve("deps/essential-forge-1.8.9.jar"), Map.of(
                "mcmod.info", "{\"modListVersion\":2,\"modList\":[{\"modid\":\"essential\",\"mcversion\":\"1.8.9\"}]}"));
        TestJars.jar(tmp.resolve("deps/essential-forge-1.20.1.jar"), Map.of(
                "META-INF/mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[47,)"
                        [[mods]]
                        modId="essential"
                        version="1.0"
                        [[dependencies.essential]]
                        modId="forge"
                        versionRange="[47,)"
                        [[dependencies.essential]]
                        modId="minecraft"
                        versionRange="[1.20.1,1.20.2)"
                        """));
        TestJars.jar(tmp.resolve("deps/essential-neoforge-1.21.1.jar"), Map.of(
                "META-INF/neoforge.mods.toml", """
                        modLoader="javafml"
                        [[mods]]
                        modId="essential"
                        [[dependencies.essential]]
                        modId="minecraft"
                        versionRange="[1.21.1]"
                        """));
        TestJars.jar(tmp.resolve("deps/plain-library.jar"), Map.of("a.txt", "x"));
    }

    Profile profile(String... paths) {
        Profile p = new Profile();
        p.name = "t";
        p.source = tmp.resolve("verity.json");
        for (String s : paths) p.mods.add(Profile.ModEntry.of(s));
        return p;
    }

    static List<String> names(List<Path> paths) {
        return paths.stream().map(x -> x.getFileName().toString()).toList();
    }

    @Test
    void inspectsMetadata() throws Exception {
        ModInfo info = JarInspector.inspect(universal);
        assertThat(info.id()).isEqualTo("prometheus");
        assertThat(info.families()).containsExactlyInAnyOrder(LoaderFamily.FABRIC, LoaderFamily.FORGE_LEGACY);
        assertThat(info.constraints().get(LoaderFamily.FORGE_LEGACY).isAny()).isTrue();
        ModInfo forge = JarInspector.inspect(tmp.resolve("deps/essential-forge-1.20.1.jar"));
        assertThat(forge.families()).containsExactly(LoaderFamily.FORGE_MODERN);
        assertThat(forge.constraints().get(LoaderFamily.FORGE_MODERN).test("1.20.1")).isTrue();
        assertThat(forge.constraints().get(LoaderFamily.FORGE_MODERN).test("1.20.2")).isFalse();
    }

    @Test
    void autoAssignment() throws Exception {
        List<ModResolver.ModFile> mods = ModResolver.expand(profile("build/libs/*.jar", "deps"));
        assertThat(mods).hasSize(6);

        assertThat(names(ModResolver.assigned(mods, new Target("1.8.9", LoaderType.FORGE, null))))
                .containsExactlyInAnyOrder("prometheus-1.6.1.jar", "essential-forge-1.8.9.jar", "plain-library.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.12.2", LoaderType.FORGE, null))))
                .containsExactlyInAnyOrder("prometheus-1.6.1.jar", "plain-library.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.20.1", LoaderType.FABRIC, null))))
                .containsExactlyInAnyOrder("prometheus-1.6.1.jar", "essential-fabric-1.20.1.jar", "plain-library.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.20.1", LoaderType.FORGE, null))))
                .containsExactlyInAnyOrder("prometheus-1.6.1.jar", "essential-forge-1.20.1.jar", "plain-library.jar");
        // NeoForge 1.20.1 accepts Forge mods.toml
        assertThat(names(ModResolver.assigned(mods, new Target("1.20.1", LoaderType.NEOFORGE, null))))
                .contains("essential-forge-1.20.1.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.21.1", LoaderType.NEOFORGE, null))))
                .containsExactlyInAnyOrder("prometheus-1.6.1.jar", "essential-neoforge-1.21.1.jar", "plain-library.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.15.2", LoaderType.FABRIC, null))))
                .containsExactly("plain-library.jar");
        assertThat(ModResolver.assigned(mods, new Target("1.20.1", LoaderType.VANILLA, null))).isEmpty();
    }

    @Test
    void manualRulesOverride() throws Exception {
        Profile p = profile("deps/plain-library.jar", "deps/essential-fabric-1.20.1.jar");
        p.mods.get(0).loaders = List.of(LoaderType.FORGE);
        p.mods.get(0).minecraft = "<=1.12.2";
        p.mods.get(1).match = Profile.Match.ALWAYS;
        List<ModResolver.ModFile> mods = ModResolver.expand(p);
        assertThat(names(ModResolver.assigned(mods, new Target("1.8.9", LoaderType.FORGE, null))))
                .containsExactlyInAnyOrder("plain-library.jar", "essential-fabric-1.20.1.jar");
        assertThat(names(ModResolver.assigned(mods, new Target("1.20.1", LoaderType.FORGE, null))))
                .containsExactly("essential-fabric-1.20.1.jar");
        assertThat(ModResolver.decide(mods.get(0), new Target("1.20.1", LoaderType.FORGE, null)).reason()).contains("minecraft");
    }

    @Test
    void missingPathsAreConfigErrors() {
        assertThatThrownBy(() -> ModResolver.expand(profile("nope.jar"))).isInstanceOf(ProfileException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> ModResolver.expand(profile("deps/*.zip"))).isInstanceOf(ProfileException.class).hasMessageContaining("No jars");
    }
}

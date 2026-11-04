package dev.verity.core.mods;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verity.core.TestJars;
import dev.verity.core.home.VerityHome;
import dev.verity.core.meta.LoaderType;
import dev.verity.core.modrinth.FakeModrinth;
import dev.verity.core.modrinth.ModrinthClient;
import dev.verity.core.modrinth.ModrinthResolver;
import dev.verity.core.net.Http;
import dev.verity.core.net.MetaCache;
import dev.verity.core.profile.Profile;
import dev.verity.core.run.Target;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPlannerTest {
    @TempDir
    Path tmp;

    @Test
    void mixesLocalAndModrinthAndReportsMissingEntries() throws Exception {
        try (FakeModrinth fake = FakeModrinth.essential(tmp.resolve("files"))) {
            Http http = new Http(HttpClient.newBuilder().proxy(ProxySelector.of(null)).build());
            ModrinthResolver resolver = new ModrinthResolver(new ModrinthClient(new MetaCache(http, tmp.resolve("c"), Duration.ofMinutes(5)), fake.base));
            VerityHome home = new VerityHome(tmp.resolve("home"));

            // Prometheus-like universal jar: Fabric >=1.16 + LaunchWrapper tweaker
            TestJars.jar(tmp.resolve("target/prometheus-1.6.1.jar"), Map.of(
                    "fabric.mod.json", "{\"id\":\"prometheus\",\"depends\":{\"minecraft\":\">=1.16\"}}",
                    "mcmod.info", "[{\"modid\":\"prometheus\"}]"), Map.of("TweakClass", "x.Tweaker"));
            Profile p = new Profile();
            p.source = tmp.resolve("verity.json");
            p.mods.add(Profile.ModEntry.of("target/prometheus-*.jar"));
            p.mods.add(Profile.ModEntry.modrinth("essential"));

            List<Target> targets = List.of(new Target("1.20.1", LoaderType.FABRIC, null), new Target("1.8.9", LoaderType.FORGE, null),
                    new Target("1.12.2", LoaderType.FORGE, null), new Target("1.20.1", LoaderType.VANILLA, null));
            Map<Target, ModPlanner.TargetPlan> plan = ModPlanner.plan(p, targets, ModResolver.expand(p), resolver, home);

            ModPlanner.TargetPlan fabric = plan.get(targets.get(0));
            assertThat(fabric.included()).extracting(ModPlanner.PlannedMod::fileName)
                    .containsExactly("prometheus-1.6.1.jar", "essential-fabric-1.20.1.jar", "fabric-api-1.20.1.jar");
            assertThat(fabric.missingEntries()).isEmpty();
            assertThat(fabric.downloads()).hasSize(2);
            assertThat(fabric.paths().get(1).startsWith(home.modsCache())).isTrue();
            assertThat(fabric.included().get(2).reason()).contains("required by essential");

            assertThat(plan.get(targets.get(1)).included()).extracting(ModPlanner.PlannedMod::fileName)
                    .containsExactly("prometheus-1.6.1.jar", "essential-forge-1.8.9.jar");
            assertThat(plan.get(targets.get(2)).missingEntries()).containsExactly("modrinth:essential");
            assertThat(plan.get(targets.get(3)).missingEntries()).containsExactly("target/prometheus-*.jar", "modrinth:essential");

            // manual rules apply to Modrinth entries too
            p.mods.get(1).loaders = List.of(LoaderType.FORGE);
            ModPlanner.TargetPlan ruled = ModPlanner.plan(p, List.of(targets.get(0)), ModResolver.expand(p), resolver, home).get(targets.get(0));
            assertThat(ruled.missingEntries()).containsExactly("modrinth:essential");
            assertThat(ruled.mods().get(1).reason()).contains("rule: loaders");
        }
    }
}

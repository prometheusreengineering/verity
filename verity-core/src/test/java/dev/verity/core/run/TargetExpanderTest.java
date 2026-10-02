package dev.verity.core.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.profile.Profile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TargetExpanderTest {
    static final Map<LoaderType, List<String>> VERSIONS = Map.of(
            LoaderType.VANILLA, List.of("1.21.10", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1", "1.12.2", "1.8.9"),
            LoaderType.FABRIC, List.of("1.21.10", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1"),
            LoaderType.NEOFORGE, List.of("1.21.10", "1.21.1", "1.20.6", "1.20.4", "1.20.1"),
            LoaderType.FORGE, List.of("1.21.10", "1.20.1", "1.12.2", "1.8.9"));

    final TargetExpander expander = new TargetExpander(new TargetExpander.Catalog() {
        @Override
        public List<String> gameVersions(LoaderType loader) {
            return VERSIONS.get(loader);
        }

        @Override
        public String latestRelease() {
            return "1.21.10";
        }
    });

    @Test
    void explicitAndSelectors() throws Exception {
        Profile p = new Profile();
        p.targets.add(Profile.TargetSpec.of("1.8.9", LoaderType.FORGE));
        Profile.TargetSpec sel = new Profile.TargetSpec();
        sel.select = new Profile.Selector();
        sel.select.minecraft = ">=1.20";
        sel.select.latestPatchOnly = true;
        sel.select.loaders = List.of(LoaderType.FABRIC, LoaderType.NEOFORGE);
        p.targets.add(sel);
        p.targets.add(Profile.TargetSpec.of("latest", null));
        Profile.TargetSpec disabled = Profile.TargetSpec.of("1.12.2", LoaderType.FORGE);
        disabled.enabled = false;
        p.targets.add(disabled);

        List<String> ids = expander.expand(p).stream().map(Target::id).toList();
        assertThat(ids).containsExactly("1.8.9-forge", "1.21.10-fabric", "1.20.6-fabric", "1.21.10-neoforge", "1.20.6-neoforge",
                "1.21.10-vanilla");
    }

    @Test
    void limitAndPinnedLoader() throws Exception {
        Profile p = new Profile();
        Profile.TargetSpec sel = new Profile.TargetSpec();
        sel.select = new Profile.Selector();
        sel.select.minecraft = "*";
        sel.select.loaders = List.of(LoaderType.FORGE);
        sel.select.limit = 2;
        p.targets.add(sel);
        Profile.TargetSpec pinned = Profile.TargetSpec.of("1.20.1", LoaderType.FABRIC);
        pinned.loaderVersion = "0.16.14";
        p.targets.add(pinned);
        List<Target> t = expander.expand(p);
        assertThat(t).extracting(Target::id).containsExactly("1.21.10-forge", "1.20.1-forge", "1.20.1-fabric-0.16.14");
        assertThat(t.get(2).matchesFilter("1.20.1/fabric")).isTrue();
        assertThat(t.get(2).matchesFilter("fabric")).isTrue();
        assertThat(t.get(0).matchesFilter("1.20.1")).isFalse();
    }
}

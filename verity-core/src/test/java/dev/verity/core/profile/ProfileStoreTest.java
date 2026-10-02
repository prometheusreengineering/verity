package dev.verity.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.verity.core.home.VerityHome;
import dev.verity.core.meta.LoaderType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {
    @TempDir
    Path tmp;

    @Test
    void readsDocumentedFormat() throws Exception {
        Path f = tmp.resolve("repo/verity.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                { "name": "essential",
                  "targets": [ {"minecraft":"1.8.9","loader":"forge","loaderVersion":"recommended"},
                               {"select":{"minecraft":">=1.20","latestPatchOnly":true,"loaders":["fabric","neoforge"]}} ],
                  "mods": [ {"path":"build/libs/*.jar"}, {"path":"deps/","match":"auto"}, {"path":"x.jar","loaders":["forge"],"minecraft":"<=1.12.2"} ],
                  "files": [ {"from":"cfg/options.txt","to":"options.txt"} ],
                  "jvm": {"memoryMb":3072,"args":["-Dfoo=bar"]},
                  "display": {"mode":"headless","width":854,"height":480,"softwareGl":"auto"},
                  "account": {"type":"offline","username":"Tester"},
                  "test": {"startupTimeoutSec":300,"timeoutSec":600},
                  "run": {"maxParallel":3,"keepInstances":true} }
                """);
        Profile p = ProfileStore.load(f);
        assertThat(p.targets.get(0).loader).isEqualTo(LoaderType.FORGE);
        assertThat(p.targets.get(1).select.loaders).containsExactly(LoaderType.FABRIC, LoaderType.NEOFORGE);
        assertThat(p.mods.get(2).hasRules()).isTrue();
        assertThat(p.jvm.memoryMb).isEqualTo(3072);
        assertThat(p.display.mode).isEqualTo(Profile.DisplayMode.HEADLESS);
        assertThat(p.run.maxParallel).isEqualTo(3);
        assertThat(p.baseDir()).isEqualTo(f.getParent());

        ProfileStore store = new ProfileStore(new VerityHome(tmp.resolve("home")).ensureCreated());
        Profile imported = store.importProfile(f);
        assertThat(Path.of(imported.mods.get(0).path)).isEqualTo(f.getParent().resolve("build/libs/*.jar"));
        assertThat(store.list()).extracting(x -> x.name).containsExactly("essential");
        assertThat(store.resolve("essential").jvm.args).containsExactly("-Dfoo=bar");
        assertThat(store.resolve(f.toString()).name).isEqualTo("essential");
        assertThatThrownBy(() -> store.resolve("missing")).isInstanceOf(ProfileException.class);

        Profile copy = ProfileStore.copy(imported);
        copy.name = "copy";
        store.save(copy);
        assertThat(store.list()).hasSize(2);
        assertThat(store.delete(copy)).isTrue();
        assertThat(store.list()).hasSize(1);
    }
}

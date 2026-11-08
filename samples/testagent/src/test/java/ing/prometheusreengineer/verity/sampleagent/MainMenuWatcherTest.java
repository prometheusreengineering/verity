package ing.prometheusreengineer.verity.sampleagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainMenuWatcherTest {
    @TempDir
    Path tmp;

    /** A stack, innermost frame first like {@link Thread#getStackTrace()}: each entry is "class.method". */
    static StackTraceElement[] stack(String... frames) {
        StackTraceElement[] out = new StackTraceElement[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int dot = frames[i].lastIndexOf('.');
            out[i] = new StackTraceElement(frames[i].substring(0, dot), frames[i].substring(dot + 1), null, -1);
        }
        return out;
    }

    @Test
    void findsTheLoaderRuntimeTitleScreens() throws Exception {
        MainMenuWatcher fabric = MainMenuWatcher.forTarget("1.20.1-fabric", null);
        assertThat(fabric.sample(stack("org.lwjgl.glfw.GLFW.glfwSwapBuffers", "net.minecraft.class_310.method_1523",
                "net.minecraft.class_310.method_1514", "net.minecraft.client.main.Main.main"), 0)).isNull();
        assertThat(fabric.sample(stack("net.minecraft.class_442.method_25394", "net.minecraft.class_757.method_3192",
                "net.minecraft.class_310.method_1514", "net.minecraft.client.main.Main.main",
                "net.fabricmc.loader.impl.launch.knot.KnotClient.main"), 0)).contains("class_442");

        MainMenuWatcher forge = MainMenuWatcher.forTarget("1.12.2-forge", null);
        assertThat(forge.sample(stack("net.minecraft.client.gui.GuiMainMenu.drawScreen", "net.minecraft.client.Minecraft.run",
                "net.minecraft.client.main.Main.main", "net.minecraft.launchwrapper.Launch.main"), 0)).contains("GuiMainMenu");
        assertThat(MainMenuWatcher.forTarget("1.21.1-neoforge", null).sample(stack(
                "net.minecraft.client.gui.screens.TitleScreen$1.render", "net.minecraft.client.Minecraft.run",
                "net.minecraft.client.main.Main.main"), 0)).contains("TitleScreen");
    }

    @Test
    void ignoresThreadsThatAreNotRunningMain() throws Exception {
        MainMenuWatcher w = MainMenuWatcher.forTarget("1.20.1-forge", null);
        assertThat(w.sample(stack("net.minecraft.client.gui.screens.TitleScreen.render", "java.lang.Thread.run"), 0)).isNull();
        // Frames below Main.main (the launcher) don't count either.
        assertThat(MainMenuWatcher.mainIndex(stack("a.b", "net.minecraft.client.main.Main.main", "x.TitleScreen.main"))).isEqualTo(1);
    }

    @Test
    void vanillaUsesMojangMappingsThenTheHardcodedNames() throws Exception {
        Path mappings = tmp.resolve("client.txt");
        Files.writeString(mappings, "# comment\nnet.minecraft.client.Minecraft -> dyr:\n"
                + "net.minecraft.client.gui.screens.TitleScreen -> eqm:\n    int field -> a\n");
        MainMenuWatcher vanilla = MainMenuWatcher.forTarget("1.20.1-vanilla", mappings.toString());
        assertThat(vanilla.obfuscatedNames()).containsExactly("eqm");
        assertThat(vanilla.usesLoopRule()).isFalse();
        assertThat(vanilla.sample(stack("eqm.a", "dyr.f", "net.minecraft.client.main.Main.main"), 0)).contains("eqm");
        assertThat(vanilla.sample(stack("eqx.a", "dyr.f", "net.minecraft.client.main.Main.main"), 0)).isNull();

        assertThat(MainMenuWatcher.forTarget("1.14.2-vanilla", null).obfuscatedNames()).containsExactly("czx");
        assertThat(MainMenuWatcher.forTarget("1.14.3-vanilla", null).obfuscatedNames()).containsExactly("dab");
        assertThat(MainMenuWatcher.titleScreenIn(new BufferedReader(new StringReader("a -> b:\n")))).isNull();
    }

    @Test
    void oldVanillaCountsTheGameLoopOnceRunHasLeftItsInitCall() throws Exception {
        MainMenuWatcher w = MainMenuWatcher.forTarget("1.12.2-vanilla", null);
        assertThat(w.usesLoopRule()).isTrue();
        String main = "net.minecraft.client.main.Main.main";
        // Constructing Minecraft isn't run() yet.
        assertThat(w.sample(stack("bib.<init>", main), 0)).isNull();
        // run() -> startGame (init), several times, then the loop.
        assertThat(w.sample(stack("cgf.a", "bib.am", "bib.a", main), 100)).isNull();
        assertThat(w.sample(stack("cgg.b", "bib.am", "bib.a", main), 3000)).isNull();
        assertThat(w.sample(stack("org.lwjgl.opengl.Display.update", "bib.az", "bib.a", main), 3100)).isNull();
        assertThat(w.sample(stack("bib.az", "bib.a", main), 4000)).isNull(); // under 2 s since init was last seen
        assertThat(w.sample(stack("bib.az", "bib.a", main), 5100)).contains("game loop running");
    }

    @Test
    void loaderTargetsNeverUseTheLoopRule() throws Exception {
        for (String t : Set.of("1.12.2-forge", "1.7.10-forge", "1.20.1-fabric", "1.20.6-neoforge")) {
            assertThat(MainMenuWatcher.forTarget(t, null).usesLoopRule()).as(t).isFalse();
        }
    }
}

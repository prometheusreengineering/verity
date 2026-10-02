package dev.verity.core.display;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Win32QuoteTest {
    @Test
    void quotesLikeMsvcrt() {
        assertThat(Win32.quote("simple")).isEqualTo("simple");
        assertThat(Win32.quote("")).isEqualTo("\"\"");
        assertThat(Win32.quote("C:\\Program Files\\Java\\bin\\java.exe")).isEqualTo("\"C:\\Program Files\\Java\\bin\\java.exe\"");
        assertThat(Win32.quote("-DFabricMcEmu= net.minecraft.client.main.Main ")).isEqualTo("\"-DFabricMcEmu= net.minecraft.client.main.Main \"");
        assertThat(Win32.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(Win32.quote("C:\\dir with space\\")).isEqualTo("\"C:\\dir with space\\\\\"");
        assertThat(Win32.commandLine(List.of("java", "-cp", "a b"))).isEqualTo("java -cp \"a b\"");
    }

    @Test
    void xvfbCommand() {
        assertThat(XvfbDisplay.command(Path.of("/usr/bin/Xvfb"), 1280, 800))
                .containsSubsequence(Path.of("/usr/bin/Xvfb").toString(), "-displayfd", "1", "-screen", "0", "1280x800x24");
    }
}

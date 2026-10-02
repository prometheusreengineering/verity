package dev.verity.core.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MesaGlTest {
    @TempDir
    Path tmp;

    @Test
    void findsSevenZipOnPathBeforeProgramFiles() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path pf = Files.createDirectories(tmp.resolve("pf/7-Zip"));
        Files.createFile(pf.resolve("7z.exe"));
        Map<String, String> env = Map.of("PATH", "\"bogus\"" + File.pathSeparator + bin, "ProgramFiles", tmp.resolve("pf").toString());

        assertThat(MesaGl.findSevenZip(env)).isEqualTo(pf.resolve("7z.exe"));
        Files.createFile(bin.resolve("7zr.exe"));
        assertThat(MesaGl.findSevenZip(env)).isEqualTo(bin.resolve("7zr.exe"));
        assertThat(MesaGl.findSevenZip(Map.of())).isNull();
    }

    @Test
    void copiesOnlyOpenGlDlls() throws Exception {
        Path x64 = Files.createDirectories(tmp.resolve("extract/x64"));
        for (String f : new String[] {"opengl32.dll", "libgallium_wgl.dll", "libglapi.dll", "dxil.dll", "vulkan_lvp.dll",
                "OpenCL.dll", "readme.txt"}) {
            Files.writeString(x64.resolve(f), f);
        }
        Path out = tmp.resolve("mesa/x64");
        MesaGl.copyGlDlls(x64, out);
        try (var list = Files.list(out)) {
            assertThat(list.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("opengl32.dll", "libgallium_wgl.dll", "libglapi.dll", "dxil.dll");
        }
        assertThatThrownBy(() -> MesaGl.copyGlDlls(tmp.resolve("nope"), out)).isInstanceOf(IOException.class);
    }
}

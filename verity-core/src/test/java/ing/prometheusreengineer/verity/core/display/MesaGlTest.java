package ing.prometheusreengineer.verity.core.display;

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
    void triesMsvcThenMingwPerReleaseNewestFirst() throws Exception {
        var releases = ing.prometheusreengineer.verity.core.util.Json.tree("""
                [ {"tag_name":"27.0.0-rc1","prerelease":true,
                   "assets":[{"name":"mesa3d-27.0.0-rc1-release-msvc.7z","browser_download_url":"u/rc"}]},
                  {"tag_name":"26.2.6","assets":[
                    {"name":"mesa3d-26.2.6-debug-mingw.7z","browser_download_url":"u/dbg"},
                    {"name":"mesa3d-26.2.6-release-mingw.7z","browser_download_url":"u/6-mingw"},
                    {"name":"mesa3d-26.2.6-release-msvc.7z","browser_download_url":"u/6-msvc"}]},
                  {"tag_name":"26.2.5","assets":[
                    {"name":"mesa3d-26.2.5-release-msvc.7z","browser_download_url":"u/5-msvc"}]} ]
                """);
        assertThat(MesaGl.candidates(releases)).containsExactly("u/6-msvc", "u/6-mingw", "u/5-msvc");
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

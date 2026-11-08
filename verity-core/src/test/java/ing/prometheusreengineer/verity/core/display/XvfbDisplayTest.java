package ing.prometheusreengineer.verity.core.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.util.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XvfbDisplayTest {
    @TempDir
    Path tmp;

    @Test
    void startsPrivateDisplayAndRunsProcessOnIt() throws Exception {
        assumeTrue(Os.current() == Os.LINUX && XvfbDisplay.which("Xvfb") != null, "Xvfb not installed");
        Profile.Display settings = new Profile.Display();
        try (DisplaySession a = new XvfbDisplay().open("a", settings); DisplaySession b = new XvfbDisplay().open("b", settings)) {
            String da = a.environment().get("DISPLAY");
            assertThat(da).matches(":\\d+");
            assertThat(b.environment().get("DISPLAY")).isNotEqualTo(da);
            assertThat(a.environment()).containsEntry("LIBGL_ALWAYS_SOFTWARE", "1");
            Path out = tmp.resolve("out.txt");
            Files.createFile(out);
            GameProcess p = a.start(new ProcessSpec(List.of("sh", "-c", "echo display=$DISPLAY"), tmp, a.environment(), out));
            assertThat(p.onExit().get()).isZero();
            assertThat(Files.readString(out).trim()).isEqualTo("display=" + da);
        }
    }
}

package ing.prometheusreengineer.verity.core.launch;

import ing.prometheusreengineer.verity.core.auth.LaunchAccount;
import ing.prometheusreengineer.verity.core.install.InstalledVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Everything needed to build a game command line. */
public record LaunchRequest(
        InstalledVersion version,
        Path java,
        Path gameDir,
        Path nativesDir,
        Path libraryDir,
        Path assetsRoot,
        Path gameAssets,
        Path loggingConfig,
        List<Path> classpath,
        LaunchAccount account,
        int memoryMb,
        int width,
        int height,
        List<String> extraJvmArgs,
        List<String> extraGameArgs,
        Map<String, String> systemProperties) {
}

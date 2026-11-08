package ing.prometheusreengineer.verity.core.display;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A process to start: command, working directory, extra environment, and the file receiving stdout+stderr. */
public record ProcessSpec(List<String> command, Path workDir, Map<String, String> environment, Path outputFile) {
}

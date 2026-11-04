package dev.verity.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstancePreparerTest {
    @TempDir
    Path tmp;

    @Test
    void seedsOnlyMissingOptions() throws Exception {
        Path options = tmp.resolve("options.txt");
        Files.writeString(options, "renderDistance:12\nlang:en_us\n");
        InstancePreparer.seedOptions(options);
        String s = Files.readString(options);
        assertThat(s).contains("renderDistance:12").doesNotContain("renderDistance:4").contains("pauseOnLostFocus:false").contains("lang:en_us");
    }
}

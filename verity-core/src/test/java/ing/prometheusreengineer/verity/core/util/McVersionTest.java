package ing.prometheusreengineer.verity.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class McVersionTest {
    @Test
    void ordersClassicAndYearVersions() {
        List<String> v = new ArrayList<>(List.of("1.21.10", "26.1", "1.8.9", "1.21.9", "1.21", "1.21-pre1", "26.1.1", "1.7.10"));
        v.sort(McVersion.COMPARATOR);
        assertThat(v).containsExactly("1.7.10", "1.8.9", "1.21-pre1", "1.21", "1.21.9", "1.21.10", "26.1", "26.1.1");
    }

    @Test
    void ordersPreReleasesNumericallyAndSnapshotsBeforeTheirRelease() {
        List<String> v = new ArrayList<>(List.of("1.20.5", "1.20.5-rc1", "1.20.5-pre10", "1.20.5-pre2", "24w14a", "24w03b",
                "1.20.4", "1.21", "24w18a", "26.1-snapshot-2", "26.1-snapshot-10", "25w46a", "26.1-pre1", "26.1", "13w41a", "1.7.10"));
        v.sort(McVersion.COMPARATOR);
        assertThat(v).containsExactly("13w41a", "1.7.10", "1.20.4", "24w03b", "24w14a", "1.20.5-pre2", "1.20.5-pre10",
                "1.20.5-rc1", "1.20.5", "24w18a", "1.21", "25w46a", "26.1-snapshot-2", "26.1-snapshot-10", "26.1-pre1", "26.1");
        assertThat(McVersion.core("24w14a")).isEqualTo("1.20.5");
    }

    @Test
    void helpers() {
        assertThat(McVersion.isRelease("1.20.1")).isTrue();
        assertThat(McVersion.isRelease("26.1")).isTrue();
        assertThat(McVersion.isRelease("24w14a")).isFalse();
        assertThat(McVersion.isRelease("1.21-rc1")).isFalse();
        assertThat(McVersion.minorLine("1.20.4")).isEqualTo("1.20");
        assertThat(McVersion.minorLine("26.1.2")).isEqualTo("26.1");
        assertThat(McVersion.core("1.20-alpha.1")).isEqualTo("1.20");
    }
}

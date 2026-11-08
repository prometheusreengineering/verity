package ing.prometheusreengineer.verity.core.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShardTest {
    static List<Target> targets(int versions) {
        List<Target> all = new ArrayList<>();
        for (int v = 0; v < versions; v++) {
            for (LoaderType l : List.of(LoaderType.FABRIC, LoaderType.FORGE, LoaderType.NEOFORGE)) {
                all.add(new Target("1." + v, l, null));
            }
        }
        return all;
    }

    @Test
    void parses() throws Exception {
        assertThat(Shard.parse("2/6")).isEqualTo(new Shard(2, 6));
        assertThat(Shard.parse(" 6 / 6 ")).isEqualTo(new Shard(6, 6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0/6", "7/6", "-1/6", "1/0", "2", "a/b", "1/2/3", ""})
    void rejects(String text) {
        assertThatThrownBy(() -> Shard.parse(text)).isInstanceOf(ProfileException.class).hasMessageContaining("Invalid shard");
    }

    @Test
    void everyTargetRunsInExactlyOneShardAndShardsAreEven() {
        List<Target> all = targets(11);
        for (int count : List.of(1, 2, 4, 6, 9, 33)) {
            List<Target> seen = new ArrayList<>();
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (int i = 1; i <= count; i++) {
                List<Target> part = new Shard(i, count).apply(all, t -> false);
                seen.addAll(part);
                min = Math.min(min, part.size());
                max = Math.max(max, part.size());
            }
            assertThat(seen).as("count %d", count).containsExactlyInAnyOrderElementsOf(all);
            assertThat(max - min).as("count %d", count).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void skippedTargetsDoNotUnbalanceShardsAndAppearOnce() {
        List<Target> all = targets(10);
        // Skipping every Forge target would leave a third of a shard's targets missing if they were dealt out too.
        Predicate<Target> skipped = t -> t.loader() == LoaderType.FORGE;
        List<Target> seenRunnable = new ArrayList<>();
        List<Target> seenSkipped = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            List<Target> part = new Shard(i, 4).apply(all, skipped);
            List<Target> runnable = part.stream().filter(skipped.negate()).toList();
            assertThat(runnable).hasSizeBetween(5, 5);
            seenRunnable.addAll(runnable);
            seenSkipped.addAll(part.stream().filter(skipped).toList());
            if (i > 1) assertThat(part).noneMatch(skipped);
        }
        assertThat(seenRunnable).hasSize(20).doesNotHaveDuplicates();
        assertThat(seenSkipped).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void doesNotDependOnTheProfilesOrderAndSpreadsLoaders() {
        List<Target> all = targets(6);
        List<Target> reversed = new ArrayList<>(all);
        java.util.Collections.reverse(reversed);
        assertThat(new Shard(2, 3).apply(reversed, t -> false)).containsExactlyInAnyOrderElementsOf(new Shard(2, 3).apply(all, t -> false));

        // 3, 6 and 9 divide the number of loaders, which is what would put one loader in each shard if ids were dealt out.
        List<Target> many = targets(18);
        for (int count : List.of(3, 6, 9)) {
            for (LoaderType loader : List.of(LoaderType.FABRIC, LoaderType.FORGE, LoaderType.NEOFORGE)) {
                for (int i = 1; i <= count; i++) {
                    long ofLoader = new Shard(i, count).apply(many, t -> false).stream().filter(t -> t.loader() == loader).count();
                    assertThat(ofLoader).as("%s in shard %d/%d", loader, i, count).isEqualTo(18 / count);
                }
            }
        }
    }

    @Test
    void moreShardsThanTargetsLeavesSomeEmpty() {
        assertThat(new Shard(4, 4).apply(targets(1), t -> false)).isEmpty();
    }
}

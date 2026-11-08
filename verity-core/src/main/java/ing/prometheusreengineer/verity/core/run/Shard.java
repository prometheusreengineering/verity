package ing.prometheusreengineer.verity.core.run;

import ing.prometheusreengineer.verity.core.profile.ProfileException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** One of {@code count} parts of a profile's targets, so that several CI jobs can split a run between them. */
public record Shard(int index, int count) {
    /** Parses {@code I/N}, where I is counted from 1. */
    public static Shard parse(String text) throws ProfileException {
        String[] parts = text.trim().split("/");
        try {
            if (parts.length == 2) {
                int index = Integer.parseInt(parts[0].trim());
                int count = Integer.parseInt(parts[1].trim());
                if (index >= 1 && index <= count) return new Shard(index, count);
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new ProfileException("Invalid shard '" + text + "': expected I/N with 1 <= I <= N, e.g. 2/6");
    }

    /**
     * Keeps this shard's part of {@code targets}, in their original order. Only targets that will really run are divided
     * up, or skipped ones would leave shards uneven. Those stay in the first shard, so reports show them once.
     */
    public List<Target> apply(List<Target> targets, Predicate<Target> skipped) {
        // Sorted, so the choice doesn't depend on the profile's order. Sorted by loader first: dealing out a run of one
        // loader gives every shard its share of it, whereas ids cycle through the loaders, and dealing those out to 3, 6 or
        // 9 shards could give each shard only one loader. The slower Forge targets need to be spread.
        List<Target> runnable = targets.stream().filter(skipped.negate())
                .sorted(Comparator.comparing((Target t) -> t.loader().id()).thenComparing(Target::id)).toList();
        Set<Target> mine = new HashSet<>();
        for (int i = index - 1; i < runnable.size(); i += count) mine.add(runnable.get(i));
        return targets.stream().filter(t -> mine.contains(t) || (index == 1 && skipped.test(t))).toList();
    }

    @Override
    public String toString() {
        return index + "/" + count;
    }
}

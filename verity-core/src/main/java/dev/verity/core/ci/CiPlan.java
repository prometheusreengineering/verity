package dev.verity.core.ci;

import java.util.List;
import java.util.Map;

/**
 * How to split a profile across CI jobs: prepare jobs that fill the caches, one per cache group and OS (only for caches
 * that don't exist yet), and test jobs ("shards") that each restore one group's caches and run part of its targets.
 */
public record CiPlan(List<Job> prepare, List<Job> shards, List<Group> groups, Summary summary) {

    /** One job. {@code key} names its caches: {@code verity-shared-<key>-<week>} and {@code verity-<os>-<key>-<week>}. */
    public record Job(String mode, CiOs os, String runner, int group, String key, String name, List<String> targets,
                      int parallel, double predictedSeconds) {
    }

    /** Consecutive Minecraft versions whose files are cached together. */
    public record Group(int index, String key, String from, String to, int targets, long sharedBytes,
                        Map<CiOs, Long> osBytes, Map<CiOs, Boolean> cached) {
    }

    public record Summary(Map<CiOs, Integer> runners, double predictedSeconds, double predictedPrepareSeconds,
                          long cacheBytes, long budgetBytes, boolean learned, List<String> skipped, List<String> warnings) {
    }
}

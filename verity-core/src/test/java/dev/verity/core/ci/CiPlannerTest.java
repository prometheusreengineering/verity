package dev.verity.core.ci;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.Target;
import dev.verity.core.run.TargetResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CiPlannerTest {
    static final String WEEK = "2026-W45";

    /** Every loader on a spread of versions, like a real "everything" profile. */
    static List<Target> many() {
        List<Target> out = new ArrayList<>();
        for (String mc : List.of("1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.19.4", "1.20.1", "1.20.4", "1.20.6", "1.21.1", "1.21.4",
                "1.21.8", "26.1", "26.2")) {
            out.add(new Target(mc, LoaderType.FORGE, null));
            if (!mc.startsWith("1.8") && !mc.startsWith("1.12")) out.add(new Target(mc, LoaderType.FABRIC, null));
            if (mc.startsWith("1.20.4") || mc.startsWith("1.21") || mc.startsWith("26")) out.add(new Target(mc, LoaderType.NEOFORGE, null));
        }
        return out;
    }

    static CiPlanner.Options options(List<CiOs> oses, int max, double knee, Set<String> existing) {
        return new CiPlanner.Options(oses, max, knee, 6, 10_000_000_000L, Map.of(), "profile", WEEK, existing, Map.of());
    }

    static CiPlan plan(List<Target> targets, CiPlanner.Options o) {
        return CiPlanner.plan(targets, List.of(), new CostModel(), o);
    }

    @Test
    void everyTargetRunsOncePerOsAndMinorVersionsStayTogether() {
        List<Target> targets = many();
        CiPlan p = plan(targets, options(List.of(CiOs.LINUX, CiOs.WINDOWS), 20, 15, Set.of()));
        for (CiOs os : CiOs.values()) {
            List<String> ran = p.shards().stream().filter(j -> j.os() == os).flatMap(j -> j.targets().stream()).toList();
            assertThat(ran).containsExactlyInAnyOrderElementsOf(targets.stream().map(Target::id).toList());
        }
        // A minor version's loaders share files, so they're in one group.
        Map<String, Set<Integer>> groupsOfMinor = p.shards().stream().flatMap(j -> j.targets().stream()
                        .map(id -> Map.entry(CiPlanner.minor(id.substring(0, id.lastIndexOf('-'))), j.group())))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
        assertThat(groupsOfMinor.values()).allMatch(s -> s.size() == 1);
        // Nothing is cached, so every group is prepared on every OS.
        assertThat(p.prepare()).hasSize(p.groups().size() * 2);
        assertThat(p.summary().runners().values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(20);
        assertThat(p.shards()).allMatch(j -> j.parallel() >= 1 && j.parallel() <= j.os().clients);
    }

    @Test
    void theSlowerOsGetsMoreRunners() {
        CiPlan p = plan(many(), options(List.of(CiOs.LINUX, CiOs.WINDOWS), 20, 15, Set.of()));
        assertThat(p.summary().runners().get(CiOs.WINDOWS)).isGreaterThanOrEqualTo(p.summary().runners().get(CiOs.LINUX));
    }

    @Test
    void runnersAreOnlyAddedWhileTheySaveEnough() {
        List<Target> few = List.of(new Target("1.20.1", LoaderType.FABRIC, null), new Target("1.20.1", LoaderType.FORGE, null),
                new Target("1.21.1", LoaderType.FABRIC, null));
        int cheap = plan(few, options(List.of(CiOs.LINUX), 20, 15, Set.of())).shards().size();
        assertThat(cheap).isLessThanOrEqualTo(3);
        int expensive = plan(few, options(List.of(CiOs.LINUX), 20, 600, Set.of())).shards().size();
        assertThat(expensive).isEqualTo(1);
        int greedy = plan(many(), options(List.of(CiOs.LINUX), 20, 0, Set.of())).shards().size();
        assertThat(greedy).isGreaterThan(plan(many(), options(List.of(CiOs.LINUX), 20, 60, Set.of())).shards().size());
    }

    @Test
    void sameInputsGiveTheSamePlan() {
        CiPlanner.Options o = options(List.of(CiOs.LINUX, CiOs.WINDOWS), 20, 15, Set.of());
        assertThat(plan(many(), o)).isEqualTo(plan(many(), o));
    }

    @Test
    void cachedGroupsArentPreparedAndTheirLayoutIsKept() {
        CiPlanner.Options cold = options(List.of(CiOs.LINUX), 20, 15, Set.of());
        CiPlan first = plan(many(), cold);
        Set<String> existing = new HashSet<>();
        for (CiPlan.Group g : first.groups()) {
            existing.add(CiPlanner.sharedKey(g.key(), WEEK));
            existing.add(CiPlanner.osKey(CiOs.LINUX, g.key(), WEEK));
        }
        CiPlan warm = plan(many(), options(List.of(CiOs.LINUX), 20, 15, existing));
        assertThat(warm.prepare()).isEmpty();
        assertThat(warm.groups()).extracting(CiPlan.Group::key).containsExactlyElementsOf(first.groups().stream().map(CiPlan.Group::key).toList());
        assertThat(warm.summary().predictedPrepareSeconds()).isZero();
    }

    @Test
    void aGroupsKeyDependsOnlyOnItsTargetsAndTheProfile() {
        CiPlanner.Options o = new CiPlanner.Options(List.of(CiOs.LINUX), 20, 15, 1, 10_000_000_000L, Map.of(), "profile", WEEK,
                Set.of(), Map.of());
        List<Target> reordered = new ArrayList<>(many());
        java.util.Collections.reverse(reordered);
        String key = plan(many(), o).groups().get(0).key();
        assertThat(plan(reordered, o).groups().get(0).key()).isEqualTo(key);
        List<Target> more = new ArrayList<>(many());
        more.add(new Target("26.3", LoaderType.FABRIC, null));
        assertThat(plan(more, o).groups().get(0).key()).isNotEqualTo(key);
        CiPlanner.Options otherProfile = new CiPlanner.Options(List.of(CiOs.LINUX), 20, 15, 1, 10_000_000_000L, Map.of(), "other",
                WEEK, Set.of(), Map.of());
        assertThat(plan(many(), otherProfile).groups().get(0).key()).isNotEqualTo(key);
    }

    @Test
    void learnedTimingsDontMoveTheCacheGroups() {
        CiPlanner.Options o = options(List.of(CiOs.LINUX), 20, 15, Set.of());
        RunReport slow = new RunReport();
        slow.host = "runner (Linux amd64)";
        for (Target t : many()) {
            TargetResult r = new TargetResult();
            r.id = t.id();
            r.status = Status.PASSED;
            r.runMs = t.minecraft().startsWith("26") ? 900_000L : 30_000L;
            slow.results.add(r);
        }
        CiPlan built = CiPlanner.plan(many(), List.of(), new CostModel(), o);
        CiPlan learned = CiPlanner.plan(many(), List.of(), new CostModel().learn(List.of(slow)), o);
        if (learned.groups().size() == built.groups().size()) {
            assertThat(learned.groups()).extracting(CiPlan.Group::key).containsExactlyElementsOf(
                    built.groups().stream().map(CiPlan.Group::key).toList());
        }
        assertThat(learned.summary().learned()).isTrue();
    }

    @Test
    void learnsRunTimesFromEarlierReports() {
        Target t = new Target("1.20.1", LoaderType.FORGE, null);
        RunReport run = new RunReport();
        run.host = "runner (Linux amd64)";
        TargetResult r = new TargetResult();
        r.id = t.id();
        r.status = Status.PASSED;
        r.durationMs = 900_000; // mostly waiting for a slot
        r.runMs = 300_000L;
        run.results.add(r);
        RunReport prepare = new RunReport();
        prepare.host = "runner (Linux amd64)";
        prepare.prepareOnly = true;
        TargetResult p = new TargetResult();
        p.id = t.id();
        p.status = Status.PREPARED;
        p.durationMs = 900_000;
        prepare.results.add(p);
        CostModel cost = new CostModel().learn(List.of(run, prepare));
        assertThat(cost.run(t, CiOs.LINUX)).isEqualTo(300);
        assertThat(cost.learnedFrom(CiOs.LINUX)).isTrue();
        assertThat(cost.run(t, CiOs.WINDOWS)).isEqualTo(CostModel.builtInRun(t, CiOs.WINDOWS));
    }
}

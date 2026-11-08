package ing.prometheusreengineer.verity.core.ci;

import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.util.McVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Chooses how many runners to use, how to split targets between them and how to group the caches, to finish as
 * soon as possible without wasting runners.
 *
 * <p>Targets are cached in groups of consecutive Minecraft versions (a minor version is never split, because all of
 * its loaders share the vanilla jar, assets and Forge's generated mappings). Each test job restores only its group's
 * caches, and each group is prepared by its own job per OS. The planner tries every group count and runner split and
 * minimizes the predicted time plus {@code kneeSeconds} per runner: a runner is only added if it saves at least that
 * much. Groups whose caches don't exist yet add the predicted prepare time, so a cached layout is kept unless another
 * one is clearly better.
 */
public final class CiPlanner {
    public record Options(List<CiOs> oses, int maxRunners, double kneeSeconds, int maxGroups, long budgetBytes,
                          Map<CiOs, String> runners, String profileHash, String week, Set<String> existingKeys,
                          Map<String, Long> cacheSizes) {
    }

    private final CostModel cost;
    private final Options o;

    private CiPlanner(CostModel cost, Options o) {
        this.cost = cost;
        this.o = o;
    }

    public static CiPlan plan(List<Target> runnable, List<String> skipped, CostModel cost, Options o) {
        return new CiPlanner(cost, o).plan(runnable, skipped);
    }

    /** One cache group's targets and keys. */
    private record GroupDef(int index, List<Target> targets, String key) {
    }

    private record Shard(GroupDef group, List<Target> targets, double seconds) {
    }

    /** One OS's shards for a layout and runner count. */
    private record OsPlan(List<Shard> shards, double seconds) {
    }

    private CiPlan plan(List<Target> runnable, List<String> skipped) {
        List<String> warnings = new ArrayList<>();
        if (runnable.isEmpty()) {
            return new CiPlan(List.of(), List.of(), List.of(), new CiPlan.Summary(Map.of(), 0, 0, 0, o.budgetBytes(),
                    false, skipped, List.of("No targets to run")));
        }
        List<List<Target>> units = units(runnable);
        int maxGroups = Math.max(1, Math.min(Math.min(o.maxGroups(), units.size()), o.maxRunners() / o.oses().size()));
        if (o.maxRunners() < o.oses().size()) throw new IllegalArgumentException("max runners must be at least one per OS");

        List<List<GroupDef>> layouts = new ArrayList<>();
        for (int g = 1; g <= maxGroups; g++) layouts.add(groups(units, g));
        calibrateSizes(layouts);
        long smallest = storage(layouts.get(0));

        double best = Double.MAX_VALUE;
        List<GroupDef> bestLayout = null;
        Map<CiOs, OsPlan> bestPlans = null;
        double bestPrepare = 0;
        for (List<GroupDef> layout : layouts) {
            // Within the budget, unless even one group doesn't fit: then caches get evicted anyway, so go for speed.
            if (smallest <= o.budgetBytes() && storage(layout) > o.budgetBytes()) continue;
            int g = layout.size();
            Map<CiOs, List<OsPlan>> byCount = new EnumMap<>(CiOs.class);
            for (CiOs os : o.oses()) byCount.put(os, allocations(layout, os, o.maxRunners() - g * (o.oses().size() - 1)));
            double prepare = prepareSeconds(layout);
            for (int[] split : splits(g)) {
                Map<CiOs, OsPlan> plans = new EnumMap<>(CiOs.class);
                double wall = 0;
                int runners = 0;
                for (int i = 0; i < o.oses().size(); i++) {
                    CiOs os = o.oses().get(i);
                    OsPlan p = byCount.get(os).get(split[i] - g);
                    plans.put(os, p);
                    wall = Math.max(wall, p.seconds());
                    runners += split[i];
                }
                double score = wall + prepare + o.kneeSeconds() * runners;
                if (score < best - 1e-9) {
                    best = score;
                    bestLayout = layout;
                    bestPlans = plans;
                    bestPrepare = prepare;
                }
            }
        }
        return build(bestLayout, bestPlans, bestPrepare, smallest, skipped, warnings);
    }

    // ---------------------------------------------------------------------------------------------- grouping

    /** Targets by minor version ({@code 1.20}, {@code 26.1}), oldest first. */
    static List<List<Target>> units(List<Target> targets) {
        Map<String, List<Target>> byMinor = new TreeMap<>(McVersion.COMPARATOR);
        for (Target t : targets) byMinor.computeIfAbsent(minor(t.minecraft()), k -> new ArrayList<>()).add(t);
        List<List<Target>> out = new ArrayList<>();
        for (List<Target> u : byMinor.values()) {
            out.add(u.stream().sorted(Comparator.comparing(Target::id)).toList());
        }
        return out;
    }

    static String minor(String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2) return mc.replaceAll("[^0-9].*", "");
        return p[0] + "." + p[1].replaceAll("[^0-9].*", "");
    }

    /**
     * Splits consecutive units into {@code g} groups with the smallest possible largest group, by built-in run cost: the
     * groups name the caches, so they mustn't move when learned timings change.
     */
    private List<GroupDef> groups(List<List<Target>> units, int g) {
        int n = units.size();
        double[] w = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double c = 0;
            for (Target t : units.get(i)) for (CiOs os : CiOs.values()) c += CostModel.builtInRun(t, os);
            w[i + 1] = w[i] + c;
        }
        // best[k][i]: smallest largest-group cost splitting the first i units into k groups.
        double[][] best = new double[g + 1][n + 1];
        int[][] cut = new int[g + 1][n + 1];
        for (double[] row : best) java.util.Arrays.fill(row, Double.MAX_VALUE);
        best[0][0] = 0;
        for (int k = 1; k <= g; k++) {
            for (int i = k; i <= n; i++) {
                for (int j = k - 1; j < i; j++) {
                    if (best[k - 1][j] == Double.MAX_VALUE) continue;
                    double v = Math.max(best[k - 1][j], w[i] - w[j]);
                    if (v < best[k][i]) {
                        best[k][i] = v;
                        cut[k][i] = j;
                    }
                }
            }
        }
        List<GroupDef> out = new ArrayList<>();
        int i = n;
        for (int k = g; k >= 1; k--) {
            int j = cut[k][i];
            List<Target> ts = new ArrayList<>();
            for (int u = j; u < i; u++) ts.addAll(units.get(u));
            out.add(0, new GroupDef(k, ts, key(ts)));
            i = j;
        }
        return out;
    }

    /** Depends only on the profile and the group's own targets, so a new Minecraft version changes one group's key. */
    private String key(List<Target> targets) {
        StringBuilder b = new StringBuilder(o.profileHash()).append('|');
        targets.stream().map(Target::id).sorted().forEach(id -> b.append(id).append(','));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sharedKey(String key, String week) {
        return "verity-shared-" + key + "-" + week;
    }

    static String osKey(CiOs os, String key, String week) {
        return "verity-" + os.runnerOs + "-" + key + "-" + week;
    }

    /** Scales the size estimates to the real sizes of this profile's caches that exist, whatever their layout. */
    private void calibrateSizes(List<List<GroupDef>> layouts) {
        double actual = 0;
        double estimated = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<GroupDef> layout : layouts) {
            for (GroupDef g : layout) {
                Long shared = o.cacheSizes().get("verity-shared-" + g.key());
                if (shared != null && seen.add("s" + g.key())) {
                    actual += shared;
                    estimated += cost.sharedBytes(g.targets());
                }
                for (CiOs os : o.oses()) {
                    Long b = o.cacheSizes().get("verity-" + os.runnerOs + "-" + g.key());
                    if (b != null && seen.add(os + g.key())) {
                        actual += b;
                        estimated += cost.osBytes(g.targets(), os);
                    }
                }
            }
        }
        if (estimated > 0) cost.scaleSizes(actual / estimated);
    }

    private long storage(List<GroupDef> layout) {
        long total = 0;
        for (GroupDef g : layout) {
            total += cost.sharedBytes(g.targets());
            for (CiOs os : o.oses()) total += cost.osBytes(g.targets(), os);
        }
        return total;
    }

    private boolean cached(GroupDef g, CiOs os) {
        return o.existingKeys().contains(sharedKey(g.key(), o.week())) && o.existingKeys().contains(osKey(os, g.key(), o.week()));
    }

    /** The slowest prepare job this layout still needs (0 when every cache exists). */
    private double prepareSeconds(List<GroupDef> layout) {
        double max = 0;
        for (GroupDef g : layout) {
            for (CiOs os : o.oses()) {
                if (cached(g, os)) continue;
                long bytes = cost.sharedBytes(g.targets()) + cost.osBytes(g.targets(), os);
                max = Math.max(max, cost.prepareJob(g.targets(), os, bytes));
            }
        }
        return max;
    }

    // -------------------------------------------------------------------------------------------- allocation

    /** Every way to give each OS at least {@code g} runners within the limit, as runner counts in {@code oses} order. */
    private List<int[]> splits(int g) {
        List<int[]> out = new ArrayList<>();
        if (o.oses().size() == 1) {
            for (int n = g; n <= o.maxRunners(); n++) out.add(new int[] {n});
        } else {
            for (int a = g; a <= o.maxRunners() - g; a++) {
                for (int b = g; a + b <= o.maxRunners(); b++) out.add(new int[] {a, b});
            }
        }
        return out;
    }

    /**
     * The best split of one OS's work for every runner count from {@code layout.size()} to {@code max}: each count
     * adds a runner to the group whose slowest shard is slowest.
     */
    private List<OsPlan> allocations(List<GroupDef> layout, CiOs os, int max) {
        int g = layout.size();
        int[] counts = new int[g];
        List<List<Shard>> shards = new ArrayList<>();
        for (int i = 0; i < g; i++) {
            counts[i] = 1;
            shards.add(lpt(layout.get(i), os, 1));
        }
        List<OsPlan> out = new ArrayList<>();
        for (int n = g; n <= max; n++) {
            List<Shard> all = new ArrayList<>();
            double wall = 0;
            int worst = 0;
            double worstSeconds = -1;
            for (int i = 0; i < g; i++) {
                double s = shards.get(i).stream().mapToDouble(Shard::seconds).max().orElse(0);
                all.addAll(shards.get(i));
                wall = Math.max(wall, s);
                // A group can't use more runners than it has targets.
                if (s > worstSeconds && counts[i] < layout.get(i).targets().size()) {
                    worstSeconds = s;
                    worst = i;
                }
            }
            out.add(new OsPlan(all, wall));
            if (worstSeconds >= 0) {
                counts[worst]++;
                shards.set(worst, lpt(layout.get(worst), os, counts[worst]));
            }
        }
        return out;
    }

    /** Longest target first onto the least loaded shard; deterministic for the same targets. */
    private List<Shard> lpt(GroupDef group, CiOs os, int count) {
        List<Target> sorted = new ArrayList<>(group.targets());
        sorted.sort(Comparator.comparingDouble((Target t) -> -cost.run(t, os)).thenComparing(Target::id));
        List<List<Target>> bins = new ArrayList<>();
        double[] load = new double[count];
        for (int i = 0; i < count; i++) bins.add(new ArrayList<>());
        for (Target t : sorted) {
            int min = 0;
            for (int i = 1; i < count; i++) if (load[i] < load[min]) min = i;
            bins.get(min).add(t);
            load[min] += cost.run(t, os);
        }
        long bytes = cost.sharedBytes(group.targets()) + cost.osBytes(group.targets(), os);
        double fixed = cost.jobOverhead(os) + cost.restore(bytes, os);
        List<Shard> out = new ArrayList<>();
        for (List<Target> b : bins) {
            if (b.isEmpty()) continue;
            out.add(new Shard(group, b, fixed + cost.runPhase(b, os, os.clients)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- output

    private CiPlan build(List<GroupDef> layout, Map<CiOs, OsPlan> plans, double prepare, long smallest, List<String> skipped,
                         List<String> warnings) {
        List<CiPlan.Job> prepareJobs = new ArrayList<>();
        List<CiPlan.Group> groups = new ArrayList<>();
        for (GroupDef g : layout) {
            Map<CiOs, Long> osBytes = new EnumMap<>(CiOs.class);
            Map<CiOs, Boolean> cached = new EnumMap<>(CiOs.class);
            long shared = cost.sharedBytes(g.targets());
            for (CiOs os : o.oses()) {
                osBytes.put(os, cost.osBytes(g.targets(), os));
                cached.put(os, cached(g, os));
                if (!cached(g, os)) {
                    prepareJobs.add(new CiPlan.Job("prepare", os, runner(os), g.index(), g.key(),
                            os.runnerOs + "-g" + g.index() + "-prepare", ids(g.targets()), os.clients,
                            cost.prepareJob(g.targets(), os, shared + osBytes.get(os))));
                }
            }
            List<String> mcs = g.targets().stream().map(Target::minecraft).sorted(McVersion.COMPARATOR).toList();
            groups.add(new CiPlan.Group(g.index(), g.key(), mcs.get(0), mcs.get(mcs.size() - 1), g.targets().size(), shared,
                    osBytes, cached));
        }
        List<CiPlan.Job> shards = new ArrayList<>();
        Map<CiOs, Integer> runners = new EnumMap<>(CiOs.class);
        double wall = 0;
        for (Map.Entry<CiOs, OsPlan> e : plans.entrySet()) {
            CiOs os = e.getKey();
            runners.put(os, e.getValue().shards().size());
            wall = Math.max(wall, e.getValue().seconds());
            Map<Integer, Integer> perGroup = new LinkedHashMap<>();
            for (Shard s : e.getValue().shards()) {
                int i = perGroup.merge(s.group().index(), 1, Integer::sum);
                shards.add(new CiPlan.Job("run", os, runner(os), s.group().index(), s.group().key(),
                        os.runnerOs + "-g" + s.group().index() + "-s" + i, ids(s.targets()),
                        Math.min(os.clients, s.targets().size()), s.seconds()));
            }
        }
        long total = storage(layout);
        if (total > o.budgetBytes()) {
            warnings.add(String.format("The caches take about %.1f GB, more than the %.1f GB budget, so GitHub will evict some"
                    + " and those runs start cold%s.", total / 1e9, o.budgetBytes() / 1e9,
                    smallest > o.budgetBytes() ? " (even one cache group doesn't fit; test fewer targets or OSes to avoid it)" : ""));
        }
        boolean learned = o.oses().stream().anyMatch(cost::learnedFrom);
        return new CiPlan(prepareJobs, shards, groups,
                new CiPlan.Summary(runners, wall, prepare, total, o.budgetBytes(), learned, skipped, warnings));
    }

    private String runner(CiOs os) {
        return o.runners().getOrDefault(os, os.defaultRunner);
    }

    private static List<String> ids(List<Target> targets) {
        return targets.stream().map(Target::id).toList();
    }
}

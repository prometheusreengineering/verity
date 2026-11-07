package dev.verity.core.ci;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.Target;
import dev.verity.core.run.TargetResult;
import dev.verity.core.util.McVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Predicts how long CI jobs take on hosted runners (4 vCPUs, 16 GB). The built-in numbers are medians from the
 * benchmarks in docs/performance.md. A project's own earlier reports replace the per-target run times, because a
 * heavy mod can be much slower than the sample mod they were measured with.
 */
public final class CostModel {
    /** Run seconds by loader and era, {Linux, Windows}, measured with the runner full of clients. */
    private static final Map<String, double[]> RUN = Map.ofEntries(
            Map.entry("fabric/old", new double[] {57, 45}), Map.entry("fabric/1.17", new double[] {81, 86}),
            Map.entry("fabric/1.21", new double[] {92, 113}), Map.entry("fabric/26", new double[] {82, 127}),
            Map.entry("forge/legacy", new double[] {60, 45}), Map.entry("forge/old", new double[] {70, 59}),
            Map.entry("forge/1.17", new double[] {80, 100}), Map.entry("forge/1.21", new double[] {84, 125}),
            Map.entry("forge/26", new double[] {89, 133}), Map.entry("neoforge/1.17", new double[] {89, 107}),
            Map.entry("neoforge/1.21", new double[] {85, 121}), Map.entry("neoforge/26", new double[] {82, 89}));

    /** Seconds per job for checkout, Java, Verity and the report upload. */
    private static final double[] JOB_OVERHEAD = {45, 60};
    /** Effective cache restore rate in bytes per second, including unpacking. */
    private static final double[] RESTORE_RATE = {100e6, 60e6};
    private static final double[] SAVE_RATE = {80e6, 50e6};
    /** Installs at once that aren't Forge processors (downloads, mostly). */
    private static final int INSTALL_CONCURRENCY = 8;
    /** Forge processors at once ({@code ForgeInstaller}'s limit). */
    private static final int PROCESSOR_SLOTS = 2;

    private static final double MB = 1e6;

    private final Map<String, List<Double>> learned = new HashMap<>();
    private final Set<CiOs> learnedOses = new HashSet<>();
    private double sizeScale = 1;

    /**
     * Uses run times from earlier reports: from launch to the end ({@code runMs}), because the whole duration includes
     * installing and waiting for a free slot, which depend on the split, not the target.
     */
    public CostModel learn(Collection<RunReport> reports) {
        for (RunReport r : reports) {
            CiOs os = CiOs.fromHost(r.host);
            if (os == null || r.prepareOnly) continue;
            for (TargetResult t : r.results) {
                if (t.status == null || !t.status.terminal() || t.status == Status.SKIPPED || t.status == Status.CANCELLED
                        || t.runMs == null || t.runMs <= 0) continue;
                learned.computeIfAbsent(os.runnerOs + "|" + t.id, k -> new ArrayList<>()).add(t.runMs / 1000.0);
                learnedOses.add(os);
            }
        }
        return this;
    }

    public boolean learnedFrom(CiOs os) {
        return learnedOses.contains(os);
    }

    /** Scales the size estimates by how far off they were for caches that exist (actual / estimated bytes). */
    public CostModel scaleSizes(double factor) {
        if (factor > 0 && Double.isFinite(factor)) sizeScale = factor;
        return this;
    }

    // ------------------------------------------------------------------------------------------------- run

    public double run(Target t, CiOs os) {
        List<Double> seen = learned.get(os.runnerOs + "|" + t.id());
        if (seen != null && !seen.isEmpty()) return median(seen);
        return builtInRun(t, os);
    }

    static double builtInRun(Target t, CiOs os) {
        String loader = t.loader() == LoaderType.VANILLA ? "fabric" : t.loader().id();
        String era = era(t.minecraft());
        double[] v = RUN.get(loader + "/" + era);
        if (v == null && era.equals("legacy")) v = RUN.get(loader + "/old");
        if (v == null) v = RUN.get(loader + "/1.17");
        if (v == null) v = RUN.get("forge/1.17");
        return v[os.ordinal()];
    }

    /** Wall time of running {@code targets} together, {@code clients} at a time, on one runner. */
    public double runPhase(List<Target> targets, CiOs os, int clients) {
        double sum = 0;
        double max = 0;
        for (Target t : targets) {
            double d = run(t, os);
            sum += d;
            max = Math.max(max, d);
        }
        return targets.isEmpty() ? 0 : Math.max(max, sum / Math.max(1, Math.min(clients, targets.size())));
    }

    // --------------------------------------------------------------------------------------------- install

    /** Seconds one install spends outside Forge processors (downloads, patching that isn't a processor). */
    static double installBase(Target t, CiOs os) {
        boolean w = os == CiOs.WINDOWS;
        return switch (t.loader()) {
            case VANILLA -> w ? 60 : 30;
            case FABRIC -> w ? 65 : 33;
            case FORGE, NEOFORGE -> processorBased(t) ? (w ? 95 : 70) : (w ? 66 : 35);
        };
    }

    /** Seconds of Forge processor work, which only {@value #PROCESSOR_SLOTS} installs can do at once. */
    static double processorWork(Target t, CiOs os) {
        if (!processorBased(t)) return 0;
        boolean w = os == CiOs.WINDOWS;
        if (era(t.minecraft()).equals("26")) return w ? 8 : 5;
        return t.loader() == LoaderType.NEOFORGE ? (w ? 40 : 25) : (w ? 32 : 19);
    }

    static boolean processorBased(Target t) {
        return (t.loader() == LoaderType.FORGE || t.loader() == LoaderType.NEOFORGE) && !era(t.minecraft()).equals("legacy");
    }

    /** A prepare job installing {@code targets} into an empty home, then saving them. */
    public double prepareJob(List<Target> targets, CiOs os, long cacheBytes) {
        double longest = 0;
        double base = 0;
        double work = 0;
        for (Target t : targets) {
            longest = Math.max(longest, installBase(t, os) + processorWork(t, os));
            base += installBase(t, os);
            work += processorWork(t, os);
        }
        double install = Math.max(longest, base / INSTALL_CONCURRENCY + work / PROCESSOR_SLOTS);
        return JOB_OVERHEAD[os.ordinal()] + install + cacheBytes / SAVE_RATE[os.ordinal()];
    }

    // ------------------------------------------------------------------------------------------------ jobs

    public double jobOverhead(CiOs os) {
        return JOB_OVERHEAD[os.ordinal()];
    }

    public double restore(long bytes, CiOs os) {
        return bytes / RESTORE_RATE[os.ordinal()];
    }

    // ----------------------------------------------------------------------------------------------- sizes

    /**
     * Estimated size of the shared cache (game files, libraries, installers) for {@code targets}. Rough: each Minecraft
     * version's client jar and libraries, each processor-based install's generated jars, and one set of assets, which
     * neighbouring versions mostly share. Corrected by {@link #scaleSizes} from caches that exist.
     */
    public long sharedBytes(List<Target> targets) {
        Set<String> versions = new HashSet<>();
        double bytes = 400 * MB;
        for (Target t : targets) {
            if (versions.add(t.minecraft())) bytes += 60 * MB;
            bytes += processorBased(t) ? 70 * MB : t.loader() == LoaderType.FORGE ? 20 * MB : 10 * MB;
        }
        return (long) (bytes * sizeScale);
    }

    /** Estimated size of the per-OS cache: Java runtimes (and Mesa and its runtime copies on Windows), natives, Xvfb. */
    public long osBytes(List<Target> targets, CiOs os) {
        Set<String> runtimes = new HashSet<>();
        for (Target t : targets) {
            runtimes.add(javaFamily(t.minecraft()));
            if (processorBased(t)) runtimes.add("17");
        }
        double bytes = os == CiOs.WINDOWS ? 300 * MB + runtimes.size() * 200 * MB : 80 * MB + runtimes.size() * 100 * MB;
        return (long) (bytes * sizeScale);
    }

    // ----------------------------------------------------------------------------------------------- helpers

    /** Coarse era of a release: legacy (≤1.12), old (1.13-1.16), 1.17 (1.17-1.20), 1.21, or 26 (year versions). */
    static String era(String mc) {
        String[] p = mc.split("[.-]");
        try {
            if (!p[0].equals("1")) return "26";
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return minor <= 12 ? "legacy" : minor <= 16 ? "old" : minor <= 20 ? "1.17" : "1.21";
        } catch (NumberFormatException e) {
            return "1.21";
        }
    }

    /** Which Java a version runs on, for sizing: 8, 16, 17, 21 or 25. */
    static String javaFamily(String mc) {
        if (McVersion.compare(mc, "1.17") < 0) return "8";
        if (McVersion.compare(mc, "1.18") < 0) return "16";
        if (McVersion.compare(mc, "1.20.5") < 0) return "17";
        if (McVersion.compare(mc, "26") < 0) return "21";
        return "25";
    }

    private static double median(List<Double> values) {
        List<Double> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}

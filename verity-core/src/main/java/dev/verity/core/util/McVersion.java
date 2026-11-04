package dev.verity.core.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numeric comparison of Minecraft version ids. Works for classic ids ({@code 1.8.9}, {@code 1.21.10}) and the
 * year-based scheme ({@code 26.1}, {@code 26.1.2}) because the leading number grows monotonically. Pre-release
 * suffixes ({@code -snapshot-3}, {@code -pre1}, {@code -rc1}) sort before the matching release, in that order and
 * numerically ({@code pre2 < pre10}). Weekly snapshots ({@code 24w14a}) sort just before the release they led to.
 */
public final class McVersion {
    private static final Pattern RELEASE = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");
    private static final Pattern WEEKLY = Pattern.compile("^(\\d\\d)w(\\d\\d)([a-z~]*)$");
    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    /**
     * Last snapshot week (yyww) before each release that had weekly snapshots. A snapshot belongs to the first
     * release whose last week is at or after it; weeks between windows belong to the next release, which is still
     * the right order. Snapshots after the last entry predate the year-based scheme's first release.
     */
    private static final int[] SNAPSHOT_WEEKS = {1208, 1230, 1242, 1311, 1326, 1343, 1434, 1607, 1621, 1644, 1718, 1822,
            1914, 1946, 2022, 2120, 2144, 2219, 2224, 2246, 2307, 2318, 2335, 2346, 2414, 2421, 2440, 2446, 2510, 2521,
            2537, 2546};
    private static final String[] SNAPSHOT_RELEASES = {"1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "1.10",
            "1.11", "1.12", "1.13", "1.14", "1.15", "1.16", "1.17", "1.18", "1.19", "1.19.1", "1.19.3", "1.19.4", "1.20",
            "1.20.2", "1.20.3", "1.20.5", "1.21", "1.21.2", "1.21.4", "1.21.5", "1.21.6", "1.21.9", "1.21.11"};
    private static final String AFTER_LAST_SNAPSHOT = "26.1";

    public static final Comparator<String> COMPARATOR = McVersion::compare;

    private McVersion() {
    }

    public static boolean isRelease(String id) {
        return id != null && id.matches("\\d+(\\.\\d+)+");
    }

    public static int compare(String a, String b) {
        String[] pa = split(a);
        String[] pb = split(b);
        if (pa == null || pb == null) return a.compareTo(b);
        int c = compareNumbers(pa[0], pb[0]);
        if (c != 0) return c;
        String sa = pa[1];
        String sb = pb[1];
        if (sa.isEmpty() && sb.isEmpty()) return 0;
        if (sa.isEmpty()) return 1;
        if (sb.isEmpty()) return -1;
        c = Integer.compare(suffixRank(sa), suffixRank(sb));
        return c != 0 ? c : compareNatural(sa, sb);
    }

    /** {numeric core, suffix}, or null when the id has no numeric core. Weekly snapshots map to their release. */
    private static String[] split(String id) {
        Matcher w = WEEKLY.matcher(id);
        if (w.matches()) return new String[] {snapshotRelease(Integer.parseInt(w.group(1) + w.group(2))), "~weekly-" + id};
        Matcher m = RELEASE.matcher(id);
        return m.matches() ? new String[] {m.group(1), m.group(2)} : null;
    }

    private static String snapshotRelease(int yyww) {
        for (int i = 0; i < SNAPSHOT_WEEKS.length; i++) if (yyww <= SNAPSHOT_WEEKS[i]) return SNAPSHOT_RELEASES[i];
        return AFTER_LAST_SNAPSHOT;
    }

    /** Weekly snapshots, then -snapshot-N, then pre-releases, then release candidates; anything else with pre-releases. */
    private static int suffixRank(String suffix) {
        if (suffix.startsWith("~weekly-")) return 0;
        String s = suffix.toLowerCase();
        if (s.contains("snapshot")) return 1;
        if (s.contains("rc")) return 3;
        return 2;
    }

    /** Compares digit runs as numbers and everything else as text, so {@code pre2 < pre10}. */
    private static int compareNatural(String a, String b) {
        Matcher ma = CHUNK.matcher(a);
        Matcher mb = CHUNK.matcher(b);
        while (true) {
            boolean ha = ma.find();
            boolean hb = mb.find();
            if (!ha || !hb) return Boolean.compare(ha, hb);
            String x = ma.group();
            String y = mb.group();
            int c = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                    ? new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y))
                    : x.compareTo(y);
            if (c != 0) return c;
        }
    }

    private static int compareNumbers(String a, String b) {
        List<Integer> pa = parts(a);
        List<Integer> pb = parts(b);
        for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
            int x = i < pa.size() ? pa.get(i) : 0;
            int y = i < pb.size() ? pb.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    public static List<Integer> parts(String numeric) {
        List<Integer> out = new ArrayList<>();
        for (String p : numeric.split("\\.")) {
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        return out;
    }

    /** The "minor line" of a release: {@code 1.20.4 -> 1.20}, {@code 26.1.2 -> 26.1}. */
    public static String minorLine(String release) {
        String[] p = release.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : release;
    }

    /** Strips a pre-release/build suffix: {@code 1.20-alpha.1 -> 1.20}; a weekly snapshot gives its release. */
    public static String core(String v) {
        String[] p = split(v);
        return p != null ? p[0] : v;
    }
}

package dev.verity.core.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numeric comparison of Minecraft release ids. Works for classic ids ({@code 1.8.9}, {@code 1.21.10}) and the
 * year-based scheme ({@code 26.1}, {@code 26.1.2}) because the leading number grows monotonically. Pre-release
 * suffixes ({@code -pre1}, {@code -rc1}, {@code -snapshot-3}) sort before the matching release.
 */
public final class McVersion {
    private static final Pattern RELEASE = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");

    public static final Comparator<String> COMPARATOR = McVersion::compare;

    private McVersion() {
    }

    public static boolean isRelease(String id) {
        return id != null && id.matches("\\d+(\\.\\d+)+");
    }

    public static int compare(String a, String b) {
        Matcher ma = RELEASE.matcher(a);
        Matcher mb = RELEASE.matcher(b);
        if (!ma.matches() || !mb.matches()) return a.compareTo(b);
        int c = compareNumbers(ma.group(1), mb.group(1));
        if (c != 0) return c;
        String sa = ma.group(2);
        String sb = mb.group(2);
        if (sa.isEmpty() && sb.isEmpty()) return 0;
        if (sa.isEmpty()) return 1;
        if (sb.isEmpty()) return -1;
        return sa.compareTo(sb);
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

    /** Strips a pre-release/build suffix: {@code 1.20-alpha.1 -> 1.20}. */
    public static String core(String v) {
        Matcher m = RELEASE.matcher(v);
        return m.matches() ? m.group(1) : v;
    }
}

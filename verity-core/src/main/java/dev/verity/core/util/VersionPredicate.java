package dev.verity.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Matches Minecraft release ids. Understands the syntaxes found in the wild:
 * <ul>
 *   <li>{@code *}, empty → anything</li>
 *   <li>{@code 1.20.1} → exactly (or "anything" under {@link #parseMaven} soft-requirement semantics)</li>
 *   <li>{@code 1.20.x}, {@code 1.20.*} → the 1.20 line</li>
 *   <li>{@code >=1.16 <1.21}, {@code <=1.12.2}, {@code >1.8}, {@code =1.20.1} (space = AND)</li>
 *   <li>{@code ~1.20.1} (same minor), {@code ^1.20} (same major) — Fabric</li>
 *   <li>{@code [1.8.9,1.12.2]}, {@code [1.20,)}, {@code (,1.13)} — Maven / Forge mods.toml</li>
 *   <li>{@code A || B} and top-level commas → OR</li>
 * </ul>
 * Pre-release suffixes on bounds ({@code >=1.20-alpha}) are ignored.
 */
public final class VersionPredicate implements Predicate<String> {
    public static final VersionPredicate ANY = new VersionPredicate("*", v -> true);

    private final String source;
    private final Predicate<String> test;

    private VersionPredicate(String source, Predicate<String> test) {
        this.source = source;
        this.test = test;
    }

    @Override
    public boolean test(String version) {
        return test.test(version);
    }

    public boolean isAny() {
        return this == ANY;
    }

    @Override
    public String toString() {
        return source;
    }

    public static VersionPredicate parse(String spec) {
        return parse(spec, false);
    }

    /** Maven semantics: a bare version without brackets is only a recommendation and matches everything. */
    public static VersionPredicate parseMaven(String spec) {
        return parse(spec, true);
    }

    public static VersionPredicate anyOf(List<String> specs) {
        if (specs == null || specs.isEmpty()) return ANY;
        List<VersionPredicate> ps = specs.stream().map(VersionPredicate::parse).toList();
        if (ps.stream().anyMatch(VersionPredicate::isAny)) return ANY;
        return new VersionPredicate(String.join(" || ", specs), v -> ps.stream().anyMatch(p -> p.test(v)));
    }

    private static VersionPredicate parse(String spec, boolean mavenSoft) {
        if (spec == null) return ANY;
        String s = spec.trim();
        if (s.isEmpty() || s.equals("*") || s.equalsIgnoreCase("any")) return ANY;
        List<String> alternatives = splitAlternatives(s);
        List<Predicate<String>> parts = new ArrayList<>();
        for (String alt : alternatives) {
            String a = alt.trim();
            if (a.isEmpty()) continue;
            if (a.equals("*")) return ANY;
            if (a.startsWith("[") || a.startsWith("(")) parts.add(interval(a));
            else if (mavenSoft && !a.contains(" ") && Character.isDigit(a.charAt(0))) return ANY;
            else parts.add(conjunction(a));
        }
        return new VersionPredicate(s, v -> parts.stream().anyMatch(p -> p.test(v)));
    }

    private static List<String> splitAlternatives(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("\\|\\|")) {
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (c == '[' || c == '(') depth++;
                if (c == ']' || c == ')') depth--;
                if (c == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
                if ((c == ']' || c == ')') && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
            out.add(cur.toString());
        }
        out.removeIf(x -> x.isBlank());
        return out;
    }

    private static Predicate<String> interval(String a) {
        boolean lowInclusive = a.startsWith("[");
        boolean highInclusive = a.endsWith("]");
        String inner = a.substring(1, a.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            String exact = inner.trim();
            return v -> McVersion.compare(v, exact) == 0;
        }
        String low = inner.substring(0, comma).trim();
        String high = inner.substring(comma + 1).trim();
        return v -> {
            if (!low.isEmpty()) {
                int c = McVersion.compare(v, McVersion.core(low));
                if (c < 0 || (c == 0 && !lowInclusive)) return false;
            }
            if (!high.isEmpty()) {
                int c = McVersion.compare(v, McVersion.core(high));
                if (c > 0 || (c == 0 && !highInclusive)) return false;
            }
            return true;
        };
    }

    private static Predicate<String> conjunction(String a) {
        List<Predicate<String>> all = new ArrayList<>();
        for (String term : a.trim().split("\\s+")) all.add(term(term));
        return v -> all.stream().allMatch(p -> p.test(v));
    }

    private static Predicate<String> term(String t) {
        if (t.startsWith(">=")) return ge(t.substring(2));
        if (t.startsWith("<=")) return le(t.substring(2));
        if (t.startsWith(">")) {
            String b = McVersion.core(t.substring(1));
            return v -> McVersion.compare(v, b) > 0;
        }
        if (t.startsWith("<")) {
            String b = McVersion.core(t.substring(1));
            return v -> McVersion.compare(v, b) < 0;
        }
        if (t.startsWith("=")) return exactOrWildcard(t.substring(1));
        if (t.startsWith("~")) {
            String b = McVersion.core(t.substring(1));
            List<Integer> p = McVersion.parts(b);
            String upper = p.get(0) + "." + ((p.size() > 1 ? p.get(1) : 0) + 1);
            return v -> McVersion.compare(v, b) >= 0 && McVersion.compare(v, upper) < 0;
        }
        if (t.startsWith("^")) {
            String b = McVersion.core(t.substring(1));
            List<Integer> p = McVersion.parts(b);
            String upper = String.valueOf(p.get(0) + 1);
            return v -> McVersion.compare(v, b) >= 0 && McVersion.compare(v, upper) < 0;
        }
        return exactOrWildcard(t);
    }

    private static Predicate<String> ge(String bound) {
        String b = McVersion.core(bound);
        return v -> McVersion.compare(v, b) >= 0;
    }

    private static Predicate<String> le(String bound) {
        String b = McVersion.core(bound);
        return v -> McVersion.compare(McVersion.core(v), b) <= 0;
    }

    private static Predicate<String> exactOrWildcard(String t) {
        if (t.endsWith(".x") || t.endsWith(".X") || t.endsWith(".*")) {
            String prefix = t.substring(0, t.length() - 2);
            return v -> v.equals(prefix) || v.startsWith(prefix + ".");
        }
        return v -> McVersion.compare(v, t) == 0;
    }
}

package ing.prometheusreengineer.verity.core.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ing.prometheusreengineer.verity.core.util.Os;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A Mojang allow/disallow rule keyed on OS and launcher features. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {
    public String action;
    public OsRule os;
    public Map<String, Boolean> features;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsRule {
        public String name;
        public String version;
        public String arch;
    }

    /** The environment rules are evaluated against. */
    public record Environment(Os os, String arch, String osVersion, Map<String, Boolean> features) {
        public static Environment current() {
            return new Environment(Os.current(), Os.arch(), System.getProperty("os.version", ""), Map.of());
        }

        public Environment withFeatures(Map<String, Boolean> f) {
            return new Environment(os, arch, osVersion, f);
        }
    }

    /** Mojang semantics: no rules = allowed; otherwise the last matching rule decides, default disallow. */
    public static boolean allowed(List<Rule> rules, Environment env) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (Rule r : rules) {
            if (r.matches(env)) allowed = !"disallow".equals(r.action);
        }
        return allowed;
    }

    boolean matches(Environment env) {
        if (os != null) {
            if (os.name != null && !os.name.equals(env.os().mojangName())) return false;
            if (os.arch != null && !archMatches(os.arch, env.arch())) return false;
            if (os.version != null) {
                try {
                    if (!Pattern.compile(os.version).matcher(env.osVersion()).find()) return false;
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        if (features != null) {
            for (Map.Entry<String, Boolean> f : features.entrySet()) {
                if (env.features().getOrDefault(f.getKey(), false) != Boolean.TRUE.equals(f.getValue())) return false;
            }
        }
        return true;
    }

    private static boolean archMatches(String ruleArch, String actual) {
        return switch (ruleArch) {
            case "x86" -> actual.equals("x86");
            case "x86_64", "amd64" -> actual.equals("x86_64");
            case "arm64", "aarch64" -> actual.equals("arm64");
            default -> ruleArch.equals(actual);
        };
    }
}

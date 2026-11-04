package dev.verity.core.launch;

import dev.verity.core.meta.Argument;
import dev.verity.core.meta.Rule;
import dev.verity.core.meta.VersionJson;
import dev.verity.core.util.Os;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Builds the java command line for both the modern {@code arguments} and legacy {@code minecraftArguments} formats. */
public final class LaunchCommandBuilder {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private LaunchCommandBuilder() {
    }

    /** Mojang's Java 16.0.1 and 17.0.1 runtimes (Minecraft 1.17-1.18). */
    static final java.util.Set<String> AVX512_CRASHING_RUNTIMES = java.util.Set.of("java-runtime-alpha", "java-runtime-beta");

    /**
     * These early JDK builds crash in JIT-compiled code (EXCEPTION_ACCESS_VIOLATION within seconds) on CPUs with
     * AVX-512, e.g. Intel Rocket Lake or AMD Zen 4. Capping the JIT at AVX2 avoids it at no practical cost. The flag
     * only exists on x86 JVMs; anywhere else it would stop the JVM from starting.
     */
    static boolean limitAvx(VersionJson v, Rule.Environment env) {
        return v.javaVersion != null && AVX512_CRASHING_RUNTIMES.contains(v.javaVersion.component)
                && ("x86_64".equals(env.arch()) || "x86".equals(env.arch()));
    }

    public static List<String> build(LaunchRequest r, Rule.Environment baseEnv) {
        VersionJson v = r.version().version();
        Rule.Environment env = baseEnv.withFeatures(Map.of(
                "has_custom_resolution", true,
                "is_demo_user", false));
        Map<String, String> vars = variables(r, baseEnv.os());

        List<String> cmd = new ArrayList<>();
        cmd.add(r.java().toString());

        cmd.add("-Xmx" + r.memoryMb() + "M");
        cmd.add("-Xms" + Math.min(512, r.memoryMb()) + "M");
        // Mitigates Log4Shell on versions whose bundled log4j supports the flag.
        cmd.add("-Dlog4j2.formatMsgNoLookups=true");
        if (limitAvx(v, baseEnv)) cmd.add("-XX:UseAVX=2");
        r.systemProperties().forEach((k, val) -> cmd.add("-D" + k + "=" + val));

        List<String> jvm = new ArrayList<>();
        if (v.arguments != null && v.arguments.jvm != null) jvm.addAll(expand(v.arguments.jvm, env, vars));
        boolean jvmHasClasspath = v.arguments != null && v.arguments.jvm != null && v.arguments.jvm.stream()
                .flatMap(a -> a.values().stream()).anyMatch(s -> s.contains("${classpath}"));
        if (!jvmHasClasspath) {
            List<String> legacy = new ArrayList<>();
            if (baseEnv.os() == Os.WINDOWS) {
                legacy.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            }
            if (baseEnv.os() == Os.OSX) legacy.add("-XstartOnFirstThread");
            legacy.add("-Djava.library.path=" + vars.get("natives_directory"));
            legacy.add("-cp");
            legacy.add(vars.get("classpath"));
            jvm.addAll(0, legacy);
        }
        cmd.addAll(jvm);

        if (r.loggingConfig() != null && v.logging != null && v.logging.client != null && v.logging.client.argument != null) {
            cmd.add(v.logging.client.argument.replace("${path}", r.loggingConfig().toString()));
        }
        cmd.addAll(r.extraJvmArgs());

        cmd.add(v.mainClass);

        List<String> game = new ArrayList<>();
        boolean modernGame = v.arguments != null && v.arguments.game != null && !v.arguments.game.isEmpty();
        if (v.minecraftArguments != null && (!modernGame || onlyFeatureGated(v.arguments.game))) {
            for (String s : v.minecraftArguments.trim().split("\\s+")) game.add(substitute(s, vars));
            if (v.arguments != null && v.arguments.game != null) game.addAll(expand(v.arguments.game, env, vars));
            if (!game.contains("--width")) {
                game.addAll(List.of("--width", String.valueOf(r.width()), "--height", String.valueOf(r.height())));
            }
        } else if (modernGame) {
            game.addAll(expand(v.arguments.game, env, vars));
        }
        cmd.addAll(game);
        cmd.addAll(r.extraGameArgs());
        return cmd;
    }

    private static boolean onlyFeatureGated(List<Argument> args) {
        return args.stream().allMatch(a -> !a.rules().isEmpty());
    }

    static Map<String, String> variables(LaunchRequest r, Os os) {
        VersionJson v = r.version().version();
        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", r.account().name());
        m.put("version_name", r.version().id());
        m.put("game_directory", r.gameDir().toString());
        m.put("assets_root", r.assetsRoot().toString());
        m.put("game_assets", r.gameAssets().toString());
        m.put("assets_index_name", v.assetIndex != null ? v.assetIndex.id : (v.assets != null ? v.assets : "legacy"));
        m.put("auth_uuid", r.account().uuidNoDashes());
        m.put("auth_access_token", r.account().accessToken());
        m.put("auth_session", "token:" + r.account().accessToken() + ":" + r.account().uuidNoDashes());
        m.put("auth_xuid", r.account().xuid() == null ? "0" : r.account().xuid());
        m.put("clientid", "0");
        m.put("user_type", r.account().userType());
        m.put("user_properties", "{}");
        m.put("version_type", v.type != null ? v.type : "release");
        m.put("resolution_width", String.valueOf(r.width()));
        m.put("resolution_height", String.valueOf(r.height()));
        m.put("natives_directory", r.nativesDir().toString());
        m.put("launcher_name", "verity");
        m.put("launcher_version", "0.1");
        m.put("library_directory", r.libraryDir().toString());
        m.put("classpath_separator", os.classpathSeparator());
        LinkedHashSet<String> cp = new LinkedHashSet<>();
        for (Path p : r.classpath()) cp.add(p.toString());
        m.put("classpath", String.join(os.classpathSeparator(), cp));
        return m;
    }

    static List<String> expand(List<Argument> args, Rule.Environment env, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Argument a : args) {
            if (!Rule.allowed(a.rules(), env)) continue;
            for (String s : a.values()) out.add(substitute(s, vars));
        }
        return out;
    }

    static String substitute(String s, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Renders a command for logs, quoting arguments with spaces and hiding the access token. */
    public static String describe(List<String> cmd, String accessToken) {
        return cmd.stream()
                .map(s -> accessToken != null && accessToken.length() > 4 ? s.replace(accessToken, "<token>") : s)
                .map(s -> s.contains(" ") ? '"' + s + '"' : s)
                .collect(Collectors.joining(" "));
    }
}

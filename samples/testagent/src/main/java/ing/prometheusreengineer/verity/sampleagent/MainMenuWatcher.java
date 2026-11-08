package ing.prometheusreengineer.verity.sampleagent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides, from samples of the render thread's stack, when the game has reached its main menu. Pure logic: the agent
 * feeds it stacks and the time.
 * <p>
 * The render thread is the one running {@code net.minecraft.client.main.Main.main}, the entry point every version keeps
 * unobfuscated. The main menu is open when a frame above it belongs to the title screen class, whose name depends on
 * the runtime: Fabric's intermediary {@code class_442}, Forge's MCP names before 1.17, Mojang's names after, or for
 * vanilla the obfuscated name from Mojang's mappings. Vanilla before 1.14 has no mappings, but loads synchronously:
 * {@code Minecraft.run()} calls its init method, which ends by opening the main menu, then loops; so the menu is open
 * once {@code run()} has moved on from the init call.
 */
final class MainMenuWatcher {
    static final String MAIN = "net.minecraft.client.main.Main";

    /** Title screen class names in the loader runtimes (matched against a frame's simple class name). */
    static final Set<String> NAMED = Collections.unmodifiableSet(new HashSet<String>(java.util.Arrays.asList(
            "class_442",      // Fabric intermediary, every version
            "GuiMainMenu",    // Forge 1.7.10-1.13 (MCP)
            "MainMenuScreen", // Forge 1.14-1.16 (MCP)
            "TitleScreen"))); // Forge/NeoForge 1.17+ and unobfuscated vanilla (Mojang names)

    /** Vanilla 1.14-1.14.3: asynchronous loading, no Mojang mappings. From Fabric's intermediary for class_442. */
    static final Map<String, String> VANILLA_1_14;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("1.14", "czv");
        m.put("1.14.1", "czx");
        m.put("1.14.2", "czx");
        m.put("1.14.3", "dab");
        VANILLA_1_14 = Collections.unmodifiableMap(m);
    }

    /** How long a call under {@code Main.main} must stay on the stack to count as {@code Minecraft.run()}. */
    static final long RUN_SETTLE_MS = 1000;
    /** How long {@code run()} must stay off its init call before the loop counts as running (1.7.10-1.12.2). */
    static final long LOOP_SETTLE_MS = 2000;

    /** How old vanilla reaches its main menu, with no names to go by. */
    enum LoopRule {
        /** Not used: the title screen's name is known. */
        NONE,
        /** 1.13.x: the constructor loads everything, so the menu is up once {@code run()} is running. */
        RUN,
        /** 1.7.10-1.12.2: {@code run()} calls {@code startGame()}, which ends by opening the menu, then loops. */
        RUN_LEFT_INIT
    }

    private final Set<String> obfuscated = new HashSet<String>();
    private final LoopRule loopRule;

    private String run;
    private long runSince = -1;
    private String initCall;
    private long lastInitSeen = -1;

    private MainMenuWatcher(Set<String> obfuscated, LoopRule loopRule) {
        this.obfuscated.addAll(obfuscated);
        this.loopRule = loopRule;
    }

    /**
     * @param target   {@code verity.target}, e.g. {@code 1.20.1-fabric} or {@code 1.12.2-vanilla}
     * @param mappings {@code verity.mappings}: Mojang's client mappings, or null
     */
    static MainMenuWatcher forTarget(String target, String mappings) throws IOException {
        String mc = target == null ? "" : target.contains("-") ? target.substring(0, target.lastIndexOf('-')) : target;
        boolean vanilla = target != null && target.endsWith("-vanilla");
        Set<String> obf = new HashSet<String>();
        if (vanilla) {
            String fromMappings = null;
            if (mappings != null) {
                BufferedReader r = Files.newBufferedReader(Paths.get(mappings), StandardCharsets.UTF_8);
                try {
                    fromMappings = titleScreenIn(r);
                } finally {
                    r.close();
                }
            }
            if (fromMappings != null) obf.add(fromMappings);
            else if (VANILLA_1_14.containsKey(mc)) obf.add(VANILLA_1_14.get(mc));
        }
        // Vanilla before 1.14 has no mappings and loads synchronously. Later vanilla without mappings (26.x) isn't
        // obfuscated, so its TitleScreen matches by name.
        LoopRule rule = LoopRule.NONE;
        int minor = minorOf(mc);
        if (vanilla && obf.isEmpty() && minor >= 0 && minor < 14) rule = minor == 13 ? LoopRule.RUN : LoopRule.RUN_LEFT_INIT;
        return new MainMenuWatcher(obf, rule);
    }

    /** The minor version of a {@code 1.x[.y]} version, or -1 for anything else (e.g. 26.1). */
    static int minorOf(String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2 || !p[0].equals("1")) return -1;
        try {
            return Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The obfuscated name of Mojang's {@code TitleScreen} in a ProGuard mappings file, or null. */
    static String titleScreenIn(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("net.minecraft.client.gui.screens.TitleScreen -> ")) {
                String obf = line.substring(line.indexOf(" -> ") + 4).trim();
                return obf.endsWith(":") ? obf.substring(0, obf.length() - 1) : obf;
            }
        }
        return null;
    }

    /** Index of the {@code Main.main} frame, or -1 if this isn't the render thread. */
    static int mainIndex(StackTraceElement[] stack) {
        for (int i = stack.length - 1; i >= 0; i--) {
            if (MAIN.equals(stack[i].getClassName()) && "main".equals(stack[i].getMethodName())) return i;
        }
        return -1;
    }

    static String simpleName(String className) {
        String s = className.substring(className.lastIndexOf('.') + 1);
        int inner = s.indexOf('$');
        return inner > 0 ? s.substring(0, inner) : s;
    }

    /** How the main menu was recognised, once it has been; null until then. {@code stack} is the render thread's. */
    String sample(StackTraceElement[] stack, long nowMillis) {
        int main = mainIndex(stack);
        if (main < 0) return null;
        for (int i = 0; i < main; i++) {
            String cls = stack[i].getClassName();
            String simple = simpleName(cls);
            if (NAMED.contains(simple) || obfuscated.contains(simple)) return "title screen " + cls;
        }
        if (loopRule == LoopRule.NONE || main < 1) return null;

        // Minecraft.run(): a method (not a constructor or static init) of an obfuscated game class, which before 1.14
        // all sit in the default package, unlike every library, that Main.main stays in.
        StackTraceElement top = stack[main - 1];
        String call = top.getClassName() + "." + top.getMethodName();
        boolean gameMethod = top.getClassName().indexOf('.') < 0 && !top.getMethodName().startsWith("<");
        if (!gameMethod || !call.equals(run)) {
            run = gameMethod ? call : null;
            runSince = gameMethod ? nowMillis : -1;
            initCall = null;
            lastInitSeen = -1;
            return null;
        }
        if (nowMillis - runSince < RUN_SETTLE_MS) return null;
        if (loopRule == LoopRule.RUN) return "game loop running (" + run + ")";

        if (main < 2) return null;
        String callee = stack[main - 2].getClassName() + "." + stack[main - 2].getMethodName();
        if (initCall == null) initCall = callee;
        if (callee.equals(initCall)) {
            lastInitSeen = nowMillis;
            return null;
        }
        if (nowMillis - lastInitSeen >= LOOP_SETTLE_MS) return "game loop running (" + run + " left " + initCall + ")";
        return null;
    }

    LoopRule loopRule() {
        return loopRule;
    }

    boolean usesLoopRule() {
        return loopRule != LoopRule.NONE;
    }

    Set<String> obfuscatedNames() {
        return Collections.unmodifiableSet(obfuscated);
    }
}

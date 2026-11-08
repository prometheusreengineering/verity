package ing.prometheusreengineer.verity.sampleagent;

import ing.prometheusreengineer.verity.api.Verity;
import java.lang.instrument.Instrumentation;

/**
 * Attached with {@code -javaagent} (a profile mod entry with {@code "agent": true}). Waits for the game's main menu by
 * sampling the render thread's stack, holds a few seconds, checks that the sample mod loaded (loader targets), and
 * calls {@link Verity#finish()}. Works on vanilla and every loader, since it needs neither.
 * <p>
 * System properties: {@code verity.sample.menuTimeout} (seconds to wait for the menu, default 280),
 * {@code verity.sample.hold} (seconds to keep running after it, default 5), {@code verity.sample.trace} (log how the
 * render thread's stack changes, to see what each version does).
 */
public final class AgentEntry {
    /** Set before any mod loads, so the sample mod knows the agent will finish the run. */
    public static final String PROP_AGENT = "verity.sample.agent";
    /** Set by the sample mod to the loader it was loaded by. */
    public static final String PROP_MOD = "verity.sample.mod";

    private AgentEntry() {
    }

    public static void premain(String args, Instrumentation inst) {
        if (!Verity.isActive()) return;
        System.setProperty(PROP_AGENT, "true");
        Verity.pass("agent loaded");
        final Instrumentation instrumentation = inst;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                watch(instrumentation);
            }
        }, "verity-sample-agent");
        t.setDaemon(true);
        t.start();
    }

    static void watch(Instrumentation inst) {
        long start = System.currentTimeMillis();
        long deadline = start + Integer.getInteger("verity.sample.menuTimeout", 280) * 1000L;
        boolean trace = Boolean.getBoolean("verity.sample.trace");
        String target = Verity.target();
        MainMenuWatcher watcher;
        try {
            watcher = MainMenuWatcher.forTarget(target, System.getProperty("verity.mappings"));
        } catch (Throwable e) {
            Verity.fail("read the title screen's name", e);
            Verity.finish();
            return;
        }
        if (trace) {
            Verity.log("watching for " + MainMenuWatcher.NAMED + " " + watcher.obfuscatedNames()
                    + (watcher.usesLoopRule() ? " or the game loop" : ""));
        }

        // Nothing to look for before the game loads its graphics, so don't walk stacks during early startup (listing
        // loaded classes walks none). The window comes before the menu, and before 1.14 early in startGame(). The rare
        // native crashes Windows clients have in early startup (0xC0000005, no hs_err) happen without the agent too.
        while (!graphicsLoaded(inst) && System.currentTimeMillis() < deadline) sleep(500);
        if (trace) Verity.log(String.format("%.1fs graphics classes loaded, sampling", (System.currentTimeMillis() - start) / 1000.0));

        // The title screen's render() can be a sliver of each frame (1.21.6+ only records what to draw there), so
        // sample often once it matters.
        long interval = Integer.getInteger("verity.sample.interval", 50);
        Thread render = null;
        long lastSearch = 0;
        String found = null;
        String lastChain = null;
        long lastTrace = 0;
        StackTraceElement[] stack = new StackTraceElement[0];
        while (System.currentTimeMillis() < deadline) {
            if ((render == null || !render.isAlive()) && System.currentTimeMillis() - lastSearch >= 1000) {
                render = findRenderThread();
                lastSearch = System.currentTimeMillis();
            }
            if (render != null) {
                stack = render.getStackTrace();
                long now = System.currentTimeMillis();
                found = watcher.sample(stack, now);
                if (trace && now - lastTrace >= 1000) {
                    String chain = chain(stack, 5);
                    if (!chain.equals(lastChain)) {
                        Verity.log(String.format("%.1fs %s: %s", (now - start) / 1000.0, render.getName(), chain));
                        lastChain = chain;
                    }
                    lastTrace = now;
                }
                if (found != null) break;
            }
            sleep(interval);
        }

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        if (found == null) {
            Verity.fail("reached the main menu", String.format(
                    "Not within %.0fs. %s", secs, render == null ? "No thread was running Minecraft's Main.main."
                            : "The render thread was at:\n" + frames(stack, 30)));
            Verity.finish();
            return;
        }
        Verity.pass(String.format("reached the main menu after %.0fs (%s)", secs, found));

        sleep(Integer.getInteger("verity.sample.hold", 5) * 1000L);
        Verity.pass("still running " + Integer.getInteger("verity.sample.hold", 5) + "s later");

        if (target != null && !target.endsWith("-vanilla")) {
            String mod = System.getProperty(PROP_MOD);
            if (mod != null) Verity.pass("mod loaded via " + mod);
            else Verity.fail("mod loaded", "The game reached its main menu but the sample mod never started.");
        }
        Verity.finish();
    }

    /** LWJGL's windowing or graphics bindings: OpenGL (LWJGL 2 and 3), GLFW, SDL (26.3+) or Vulkan (26.x). */
    static boolean graphicsLoaded(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.startsWith("org.lwjgl.") && (n.startsWith("org.lwjgl.opengl.") || n.startsWith("org.lwjgl.glfw.")
                    || n.startsWith("org.lwjgl.sdl.") || n.startsWith("org.lwjgl.vulkan."))) {
                return true;
            }
        }
        return false;
    }

    /** Names Minecraft gives the thread that runs {@code Main.main}: the JVM's, then 1.7.10-1.13's and 1.14+'s. */
    static final java.util.Set<String> RENDER_NAMES = new java.util.HashSet<String>(
            java.util.Arrays.asList("main", "Client thread", "Render thread"));

    /**
     * The thread running {@code net.minecraft.client.main.Main.main}, or null if there is none yet. Lists threads
     * without their stacks and walks only the few with a render thread's name.
     */
    static Thread findRenderThread() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] threads = new Thread[root.activeCount() + 16];
        int n = root.enumerate(threads, true);
        for (int i = 0; i < n; i++) {
            Thread t = threads[i];
            if (t == null || !RENDER_NAMES.contains(t.getName())) continue;
            if (MainMenuWatcher.mainIndex(t.getStackTrace()) >= 0) return t;
        }
        return null;
    }

    /** The {@code depth} frames above {@code Main.main}, outermost first. */
    static String chain(StackTraceElement[] stack, int depth) {
        int main = MainMenuWatcher.mainIndex(stack);
        if (main < 0) return "(not in Main.main)";
        StringBuilder sb = new StringBuilder();
        for (int i = main - 1; i >= 0 && i >= main - depth; i--) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
        }
        return sb.toString();
    }

    static String frames(StackTraceElement[] stack, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.length && i < max; i++) sb.append("  at ").append(stack[i]).append('\n');
        return sb.toString();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

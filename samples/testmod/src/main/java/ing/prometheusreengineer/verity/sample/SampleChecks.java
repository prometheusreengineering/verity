package ing.prometheusreengineer.verity.sample;

import ing.prometheusreengineer.verity.api.Verity;

/**
 * The loader-independent part of the sample: report that the mod was loaded. A real mod would run its own assertions
 * here (e.g. "my mixins/patches were applied", "my universal adapter resolved the right Minecraft classes").
 * <p>
 * With the sample agent attached ({@code samples/testagent}), the agent finishes the run once the game reaches its main
 * menu and checks that this mod loaded. On its own, the mod finishes after {@code verity.sample.delay} seconds (20).
 */
public final class SampleChecks {
    /** Set by the sample agent's premain, which runs before any mod. */
    static final String PROP_AGENT = "verity.sample.agent";
    /** Read by the sample agent: which loader loaded this mod. */
    static final String PROP_MOD = "verity.sample.mod";

    private static boolean started;

    private SampleChecks() {
    }

    public static synchronized void start(final String loadedVia) {
        if (started) return;
        started = true;
        if (!Verity.isActive()) return;

        Verity.pass("loaded via " + loadedVia);
        Verity.log("running on Java " + System.getProperty("java.version") + " as " + Verity.target());
        Verity.test("verity system properties visible", new Runnable() {
            @Override
            public void run() {
                if (Verity.target() == null) throw new AssertionError("verity.target missing");
            }
        });
        // System properties are shared by every class loader, so this reaches the agent's copy of the code.
        System.setProperty(PROP_MOD, loadedVia);
        if (Boolean.getBoolean(PROP_AGENT)) return;

        final int delay = Integer.getInteger("verity.sample.delay", 20);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                Verity.pass("game still running after " + delay + "s");
                Verity.finish();
            }
        }, "verity-sample");
        t.setDaemon(true);
        t.start();
    }
}

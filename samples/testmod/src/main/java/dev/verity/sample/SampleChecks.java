package dev.verity.sample;

import dev.verity.api.Verity;

/**
 * The loader-independent part of the sample: report that the mod was loaded, then let the game run for a while
 * and finish. A real mod would run its own assertions here (e.g. "my mixins/patches were applied", "my
 * universal adapter resolved the right Minecraft classes") before calling {@link Verity#finish()}.
 */
public final class SampleChecks {
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

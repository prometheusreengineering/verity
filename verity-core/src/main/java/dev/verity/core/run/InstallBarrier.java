package dev.verity.core.run;

import dev.verity.core.meta.LoaderType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Holds back Forge and NeoForge clients until every Forge-style install of their Minecraft version in the run is done.
 * Those installs share the deobfuscated client and mappings in the libraries, and an installer that expects other bytes
 * (a different Forge or NeoForge build) rewrites them, which breaks a client already running from them ("zip END
 * header not found").
 */
final class InstallBarrier {
    private final Map<String, CountDownLatch> latches = new HashMap<>();

    InstallBarrier(Collection<Target> targets) {
        Map<String, Integer> counts = new HashMap<>();
        for (Target t : targets) {
            String k = key(t);
            if (k != null) counts.merge(k, 1, Integer::sum);
        }
        counts.forEach((k, n) -> latches.put(k, new CountDownLatch(n)));
    }

    private static String key(Target t) {
        return t.loader() == LoaderType.FORGE || t.loader() == LoaderType.NEOFORGE ? t.minecraft() : null;
    }

    /** Whether {@code t} may have to wait for other installs. */
    boolean shared(Target t) {
        CountDownLatch l = latches.get(key(t));
        return l != null && l.getCount() > 0;
    }

    /** {@code t}'s install finished, or will never run; call once per target. */
    void installed(Target t) {
        CountDownLatch l = latches.get(key(t));
        if (l != null) l.countDown();
    }

    void await(Target t) throws InterruptedException {
        CountDownLatch l = latches.get(key(t));
        if (l != null) l.await();
    }
}

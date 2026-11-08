package ing.prometheusreengineer.verity.core.display;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** A running game client. */
public interface GameProcess {
    long pid();

    boolean isAlive();

    /** Completes with the exit code. */
    CompletableFuture<Integer> onExit();

    /** Stops the process and its children: polite request first, then forcibly after {@code grace}. */
    void stop(Duration grace);
}

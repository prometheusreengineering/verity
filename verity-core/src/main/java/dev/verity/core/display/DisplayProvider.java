package dev.verity.core.display;

import dev.verity.core.profile.Profile;
import java.io.IOException;
import java.util.List;

public interface DisplayProvider {
    String name();

    /** Problems that would prevent launching (missing Xvfb...) as errors, and softer warnings. */
    default List<String> warnings() {
        return List.of();
    }

    /** Throws with an actionable message if this provider cannot work on this machine. */
    default void preflight() throws IOException {
    }

    DisplaySession open(String targetId, Profile.Display settings) throws IOException;
}

package dev.verity.core.display;

import java.io.IOException;
import java.util.Map;

/** A display one client renders into. */
public interface DisplaySession extends AutoCloseable {
    /** Environment variables the game needs (e.g. {@code DISPLAY}). */
    Map<String, String> environment();

    /** Human readable, e.g. {@code Xvfb :99}. */
    String description();

    default GameProcess start(ProcessSpec spec) throws IOException {
        return JdkGameProcess.start(spec);
    }

    @Override
    void close();
}

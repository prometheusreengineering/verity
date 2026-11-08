package ing.prometheusreengineer.verity.core.display;

import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.util.Os;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Visible windows on the user's desktop. */
public final class WindowDisplay implements DisplayProvider {
    @Override
    public String name() {
        return "window";
    }

    @Override
    public void preflight() throws IOException {
        if (Os.current() == Os.LINUX && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) {
            throw new IOException("No DISPLAY is set, so windows cannot be shown. Use headless mode (--display headless).");
        }
    }

    @Override
    public DisplaySession open(String targetId, Profile.Display settings) {
        Map<String, String> env = new HashMap<>();
        if (settings.softwareGl == Profile.SoftwareGl.ON && Os.current() == Os.LINUX) {
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("GALLIUM_DRIVER", "llvmpipe");
        }
        return new DisplaySession() {
            @Override
            public Map<String, String> environment() {
                return env;
            }

            @Override
            public String description() {
                return "window";
            }

            @Override
            public void close() {
            }
        };
    }
}

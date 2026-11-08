package ing.prometheusreengineer.verity.core.display;

import ing.prometheusreengineer.verity.core.install.InstallContext;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.util.Os;

/** Picks the display implementation for a mode on this OS. */
public final class Displays {
    private Displays() {
    }

    public static DisplayProvider forMode(Profile.DisplayMode mode, InstallContext ctx) {
        if (mode == Profile.DisplayMode.WINDOW) return new WindowDisplay();
        return switch (Os.current()) {
            case LINUX -> new XvfbDisplay();
            case WINDOWS -> new Win32HiddenDesktop(ctx);
            case OSX -> new WindowDisplay(); // macOS has no practical virtual display for OpenGL
        };
    }
}

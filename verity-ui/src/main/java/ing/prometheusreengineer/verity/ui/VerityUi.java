package ing.prometheusreengineer.verity.ui;

import javafx.application.Application;

/** Launches the desktop UI. Kept separate from {@link VerityApp} so fat jars start without the JavaFX launcher check. */
public final class VerityUi {
    static volatile String homeOverride;

    private VerityUi() {
    }

    public static void open(String home) {
        homeOverride = home;
        Application.launch(VerityApp.class);
    }

    public static void main(String[] args) {
        open(args.length > 0 ? args[0] : null);
    }
}

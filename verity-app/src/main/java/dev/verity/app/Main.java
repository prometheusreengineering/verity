package dev.verity.app;

import dev.verity.cli.VerityCli;
import dev.verity.ui.VerityUi;

/** No arguments (or {@code ui}) opens the desktop UI; anything else is the CLI. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            VerityUi.open(null);
            return;
        }
        System.exit(VerityCli.execute(args));
    }
}

package ing.prometheusreengineer.verity.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "ui", description = "Open the desktop UI.")
class UiCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    VerityCli root;

    @Override
    public Integer call() throws Exception {
        try {
            Class<?> launcher = Class.forName("ing.prometheusreengineer.verity.ui.VerityUi");
            launcher.getMethod("open", String.class).invoke(null, root.home);
            return 0;
        } catch (ClassNotFoundException e) {
            System.err.println("This build of Verity has no UI (CLI-only jar). Use the desktop build to get the UI.");
            return VerityCli.CONFIG_ERROR;
        }
    }
}

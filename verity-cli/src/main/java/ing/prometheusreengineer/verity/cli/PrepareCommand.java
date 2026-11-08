package ing.prometheusreengineer.verity.cli;

import picocli.CommandLine.Command;

@Command(name = "prepare", description = "Download and install everything a profile needs without launching (e.g. to warm a CI cache).")
class PrepareCommand extends RunCommand {
    @Override
    boolean prepareOnly() {
        return true;
    }
}

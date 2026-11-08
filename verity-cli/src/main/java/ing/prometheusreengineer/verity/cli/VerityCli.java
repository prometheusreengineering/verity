package ing.prometheusreengineer.verity.cli;

import ing.prometheusreengineer.verity.core.home.VerityHome;
import ing.prometheusreengineer.verity.core.install.InstallContext;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import java.io.IOException;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code verity} command line entry point. */
@Command(name = "verity", mixinStandardHelpOptions = true, versionProvider = VerityCli.Version.class,
        description = {"Launch many Minecraft clients (vanilla, Fabric, Forge, NeoForge) in parallel to test version-independent mods."},
        subcommands = {RunCommand.class, PlanCommand.class, PrepareCommand.class, MatrixCommand.class, ProfileCommand.class, VersionsCommand.class,
                AccountCommand.class, ModrinthCommand.class, CacheCommand.class, UiCommand.class, CommandLine.HelpCommand.class})
public final class VerityCli implements Runnable {
    /** Exit codes. */
    public static final int OK = 0;
    public static final int TESTS_FAILED = 1;
    public static final int CONFIG_ERROR = 2;
    public static final int INTERNAL_ERROR = 3;

    @Option(names = "--home", description = "Verity data directory (default: $VERITY_HOME or the per-user app data folder).", scope = CommandLine.ScopeType.INHERIT)
    String home;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private InstallContext context;

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String... args) {
        CommandLine cl = commandLine();
        return cl.execute(args);
    }

    public static CommandLine commandLine() {
        CommandLine cl = new CommandLine(new VerityCli());
        cl.setCaseInsensitiveEnumValuesAllowed(true);
        cl.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            PrintWriter err = commandLine.getErr();
            if (ex instanceof ProfileException) {
                err.println(commandLine.getColorScheme().errorText("Error: " + ex.getMessage()));
                return CONFIG_ERROR;
            }
            if (ex instanceof IOException) {
                err.println(commandLine.getColorScheme().errorText("Error: " + ex.getMessage()));
                return INTERNAL_ERROR;
            }
            err.println(commandLine.getColorScheme().errorText("Unexpected error: " + ex));
            ex.printStackTrace(err);
            return INTERNAL_ERROR;
        });
        return cl;
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    VerityHome verityHome() {
        return VerityHome.resolve(home);
    }

    synchronized InstallContext context() throws IOException {
        if (context == null) context = InstallContext.open(verityHome());
        return context;
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = VerityCli.class.getPackage().getImplementationVersion();
            return new String[] {"verity " + (v != null ? v : "dev"), "Java " + System.getProperty("java.version") + " on "
                    + System.getProperty("os.name") + " " + System.getProperty("os.arch")};
        }
    }
}

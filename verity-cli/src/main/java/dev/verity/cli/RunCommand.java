package dev.verity.cli;

import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileStore;
import dev.verity.core.run.RunHandle;
import dev.verity.core.run.RunOptions;
import dev.verity.core.run.RunOrchestrator;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Shard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "Run every target of a profile in parallel and report the results.",
        footer = {"", "Exit codes: 0 all passed, 1 some target failed, 2 configuration error, 3 internal error."})
class RunCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", paramLabel = "PROFILE", description = "Profile name or path to a profile JSON file.")
    String profile;

    @Option(names = {"-o", "--only"}, split = ",", paramLabel = "FILTER",
            description = "Only run matching targets, e.g. 1.20.1-fabric, 1.8.9/forge, fabric, 1.21.1 (repeatable / comma separated).")
    List<String> only = new ArrayList<>();

    @Option(names = "--shard", paramLabel = "I/N",
            description = "Only run part I of N of the targets, to split a run across CI jobs. `prepare` ignores this.")
    String shard;

    @Option(names = {"-p", "--parallel"}, description = "Max clients at once (default: profile setting or automatic).")
    Integer parallel;

    @Option(names = {"-d", "--display"}, description = "headless or window (overrides the profile).")
    String display;

    @Option(names = {"-r", "--report-dir"}, description = "Write reports here instead of <home>/runs/<timestamp>-<profile>.")
    Path reportDir;

    @Option(names = "--keep-instances", negatable = true, description = "Reuse instance folders between runs (worlds, configs).")
    Boolean keep;

    @Option(names = {"-l", "--logs"}, description = "Stream every client's game log to the console.")
    boolean logs;

    boolean prepareOnly() {
        return false;
    }

    @Override
    public Integer call() throws Exception {
        Profile p = new ProfileStore(root.verityHome()).resolve(profile);
        RunOptions o = new RunOptions();
        o.only = only;
        o.shard = shard == null ? null : Shard.parse(shard);
        o.maxParallel = parallel;
        try {
            o.display = display == null ? null : Profile.DisplayMode.parse(display);
        } catch (IllegalArgumentException e) {
            throw new dev.verity.core.profile.ProfileException(e.getMessage());
        }
        o.reportDir = reportDir;
        o.keepInstances = keep;
        o.prepareOnly = prepareOnly();
        RunOrchestrator orchestrator = new RunOrchestrator(root.context());
        ConsoleReporter reporter = new ConsoleReporter(spec.commandLine().getOut(), spec.commandLine().getColorScheme().ansi(), logs);
        RunHandle handle = orchestrator.start(p, o, reporter);
        RunReport report = handle.report().get();
        return report.success() ? VerityCli.OK : VerityCli.TESTS_FAILED;
    }
}

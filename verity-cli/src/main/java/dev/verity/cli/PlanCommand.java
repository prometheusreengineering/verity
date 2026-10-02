package dev.verity.cli;

import dev.verity.core.install.InstallContext;
import dev.verity.core.meta.LoaderType;
import dev.verity.core.mods.ModResolver;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileStore;
import dev.verity.core.run.RunOptions;
import dev.verity.core.run.RunOrchestrator;
import dev.verity.core.run.Target;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "plan", description = "Show the targets a profile expands to and which mod jars each one receives (dry run).")
class PlanCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", paramLabel = "PROFILE")
    String profile;

    @Option(names = {"-o", "--only"}, split = ",", paramLabel = "FILTER")
    List<String> only = new ArrayList<>();

    @Option(names = "--resolve", description = "Also resolve loader versions (needs network).")
    boolean resolve;

    @Option(names = {"-v", "--verbose"}, description = "Also list excluded jars and why.")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        CommandLine.Help.Ansi ansi = spec.commandLine().getColorScheme().ansi();
        Profile p = new ProfileStore(root.verityHome()).resolve(profile);
        RunOrchestrator.validate(p);
        InstallContext ctx = root.context();
        RunOptions o = new RunOptions();
        o.only = only;
        List<Target> targets = new RunOrchestrator(ctx).targets(p, o);
        List<ModResolver.ModFile> mods = ModResolver.expand(p);
        out.println(ansi.string("@|bold " + p.name + "|@: " + targets.size() + " target(s), " + mods.size() + " mod jar(s)"));
        for (Target t : targets) {
            String lv = "";
            if (t.loader() != LoaderType.VANILLA) {
                String spec = t.loaderVersion() == null ? "recommended" : t.loaderVersion();
                if (resolve) {
                    try {
                        spec = ctx.meta().loader(t.loader()).resolve(t.minecraft(), t.loaderVersion());
                    } catch (Exception e) {
                        spec = "unavailable: " + e.getMessage();
                    }
                }
                lv = " (" + t.loader().displayName() + " " + spec + ")";
            }
            out.println(ansi.string("  @|bold " + t.id() + "|@" + lv));
            for (ModResolver.Decision d : ModResolver.decide(mods, t)) {
                if (d.included()) out.println(ansi.string("      @|green +|@ " + d.mod().path().getFileName() + "  @|faint " + d.reason() + "|@"));
                else if (verbose) out.println(ansi.string("      @|faint - " + d.mod().path().getFileName() + "  " + d.reason() + "|@"));
            }
        }
        out.flush();
        return VerityCli.OK;
    }
}

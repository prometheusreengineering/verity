package ing.prometheusreengineer.verity.cli;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "versions", description = {"List available versions.",
        "  verity versions vanilla            Minecraft releases",
        "  verity versions fabric             Minecraft versions Fabric supports",
        "  verity versions forge --mc 1.20.1  Forge versions for 1.20.1"})
class VersionsCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", defaultValue = "vanilla", paramLabel = "LOADER", description = "vanilla, fabric, forge or neoforge.")
    String loader;

    @Option(names = "--mc", description = "List loader versions for this Minecraft version.")
    String mc;

    @Option(names = {"-n", "--limit"}, defaultValue = "40", description = "Show at most this many (0 = all).")
    int limit;

    @Override
    public Integer call() throws Exception {
        LoaderType type;
        try {
            type = LoaderType.parse(loader);
        } catch (IllegalArgumentException e) {
            throw new ProfileException(e.getMessage());
        }
        var meta = root.context().meta();
        List<String> list;
        if (mc != null && type != LoaderType.VANILLA) {
            list = meta.loader(type).loaderVersions(mc);
            String rec = list.isEmpty() ? null : meta.loader(type).resolve(mc, "recommended");
            print(list, rec);
        } else {
            list = meta.gameVersions(type);
            print(list, null);
        }
        return 0;
    }

    private void print(List<String> list, String highlight) {
        PrintWriter out = spec.commandLine().getOut();
        int n = limit <= 0 ? list.size() : Math.min(limit, list.size());
        for (int i = 0; i < n; i++) out.println(list.get(i) + (list.get(i).equals(highlight) ? "  (recommended)" : ""));
        if (n < list.size()) out.println("... " + (list.size() - n) + " more (use --limit 0)");
        if (list.isEmpty()) out.println("(none)");
        out.flush();
    }
}

package ing.prometheusreengineer.verity.cli;

import ing.prometheusreengineer.verity.core.home.VerityHome;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cache", description = "Show or clean Verity's data directory.",
        subcommands = {CacheCommand.Info.class, CacheCommand.Clean.class})
class CacheCommand implements Runnable {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static Map<String, Path> parts(VerityHome h) {
        Map<String, Path> m = new LinkedHashMap<>();
        m.put("libraries", h.libraries());
        m.put("assets", h.assets());
        m.put("versions", h.versions());
        m.put("runtimes", h.runtimes());
        m.put("installers", h.installers());
        m.put("instances", h.instances());
        m.put("runs", h.runs());
        m.put("meta-cache", h.metaCache());
        m.put("tools", h.tools());
        return m;
    }

    @Command(name = "info", description = "Show where data lives and how big it is.")
    static class Info implements Callable<Integer> {
        @CommandLine.ParentCommand
        CacheCommand parent;

        @Override
        public Integer call() throws Exception {
            VerityHome h = parent.root.verityHome();
            PrintWriter out = parent.spec.commandLine().getOut();
            out.println("Verity home: " + h.root());
            long total = 0;
            for (Map.Entry<String, Path> e : parts(h).entrySet()) {
                long size = FileUtil.sizeOf(e.getValue());
                total += size;
                out.printf("  %-12s %8.1f MiB%n", e.getKey(), size / 1048576.0);
            }
            out.printf("  %-12s %8.1f MiB%n", "total", total / 1048576.0);
            out.flush();
            return 0;
        }
    }

    @Command(name = "clean", description = "Delete cached data (default: instances and run reports).")
    static class Clean implements Callable<Integer> {
        @CommandLine.ParentCommand
        CacheCommand parent;

        @Option(names = "--all", description = "Delete everything except profiles, accounts and settings.")
        boolean all;

        @Option(names = "--meta", description = "Also drop cached version lists.")
        boolean meta;

        @Override
        public Integer call() throws Exception {
            VerityHome h = parent.root.verityHome();
            PrintWriter out = parent.spec.commandLine().getOut();
            for (Map.Entry<String, Path> e : parts(h).entrySet()) {
                boolean selected = all || e.getKey().equals("instances") || e.getKey().equals("runs") || (meta && e.getKey().equals("meta-cache"));
                if (!selected) continue;
                FileUtil.deleteRecursively(e.getValue());
                out.println("Deleted " + e.getValue());
            }
            out.flush();
            return 0;
        }
    }
}

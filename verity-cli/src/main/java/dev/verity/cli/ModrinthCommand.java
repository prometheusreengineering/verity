package dev.verity.cli;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.modrinth.ModrinthClient;
import dev.verity.core.modrinth.ModrinthProject;
import dev.verity.core.modrinth.ModrinthResolver;
import dev.verity.core.modrinth.ModrinthVersion;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.run.Target;
import dev.verity.core.util.McVersion;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "modrinth", description = "Look up mods on Modrinth.",
        subcommands = {ModrinthCommand.Search.class, ModrinthCommand.Versions.class})
class ModrinthCommand implements Runnable {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    @Command(name = "search", description = "Search Modrinth for mods.")
    static class Search implements Callable<Integer> {
        @CommandLine.ParentCommand
        ModrinthCommand parent;

        @Parameters(paramLabel = "QUERY", arity = "1..*")
        List<String> query;

        @Option(names = {"-n", "--limit"}, defaultValue = "10")
        int limit;

        @Override
        public Integer call() throws Exception {
            ModrinthClient c = parent.root.context().modrinth();
            List<ModrinthProject.Hit> hits = c.search(String.join(" ", query), limit);
            for (ModrinthProject.Hit h : hits) {
                parent.out().printf("%-28s %-30s %,12d downloads%n", h.slug, h.title, h.downloads);
            }
            if (hits.isEmpty()) parent.out().println("(no results)");
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "versions", description = {"Show which Minecraft versions and loaders a Modrinth project supports.",
            "  verity modrinth versions essential",
            "  verity modrinth versions essential --mc 1.20.1 --loader fabric   (the file Verity would pick)"})
    static class Versions implements Callable<Integer> {
        @CommandLine.ParentCommand
        ModrinthCommand parent;

        @Parameters(paramLabel = "PROJECT")
        String project;

        @Option(names = "--mc")
        String mc;

        @Option(names = "--loader")
        String loader;

        @Option(names = "--channel", defaultValue = "release", description = "release, beta or alpha.")
        String channel;

        @Override
        public Integer call() throws Exception {
            ModrinthClient c = parent.root.context().modrinth();
            ModrinthResolver r = new ModrinthResolver(c);
            PrintWriter out = parent.out();
            ModrinthProject p = c.project(project);
            if (mc != null && loader != null) {
                LoaderType type;
                try {
                    type = LoaderType.parse(loader);
                } catch (IllegalArgumentException e) {
                    throw new ProfileException(e.getMessage());
                }
                List<ModrinthResolver.Resolved> picked = r.resolve(project, new Target(mc, type, null), channel, null, true);
                if (picked.isEmpty()) out.println(p.title + " has no " + channel + " file for " + type.id() + " " + mc);
                for (ModrinthResolver.Resolved x : picked) {
                    out.println((x.dependency() ? "  dependency " : "") + x.projectSlug() + " " + x.version().versionNumber + " ["
                            + x.version().versionType + "]  " + x.file().filename);
                }
                out.flush();
                return 0;
            }
            Map<String, TreeSet<String>> byLoader = new TreeMap<>();
            for (Target t : r.supportedTargets(project, channel)) {
                byLoader.computeIfAbsent(t.loader().id(), k -> new TreeSet<>(McVersion.COMPARATOR.reversed())).add(t.minecraft());
            }
            out.println(p.title + " (" + p.slug + "), " + channel + " builds:");
            byLoader.forEach((l, vs) -> out.println("  " + l + " (" + vs.size() + "): " + String.join(", ", vs)));
            if (byLoader.isEmpty()) out.println("  (none)");
            int total = byLoader.values().stream().mapToInt(TreeSet::size).sum();
            List<ModrinthVersion> all = c.versions(project);
            out.println(total + " version/loader pairs from " + all.size() + " published versions");
            out.flush();
            return 0;
        }
    }
}

package dev.verity.cli;

import dev.verity.core.meta.LoaderType;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.profile.ProfileStore;
import dev.verity.core.util.Json;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "profile", description = "Manage stored profiles.",
        subcommands = {ProfileCommand.ListCmd.class, ProfileCommand.Show.class, ProfileCommand.New.class, ProfileCommand.Remove.class,
                ProfileCommand.Import.class, ProfileCommand.Export.class})
class ProfileCommand implements Runnable {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    ProfileStore store() {
        return new ProfileStore(root.verityHome());
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    /** Parses {@code 1.20.1:fabric[:0.16.14]}, {@code 1.8.9:forge}, {@code 1.21.1} (vanilla). */
    static Profile.TargetSpec parseTarget(String s) throws ProfileException {
        String[] p = s.split("[:/]");
        Profile.TargetSpec t = new Profile.TargetSpec();
        t.minecraft = p[0];
        try {
            t.loader = p.length > 1 ? LoaderType.parse(p[1]) : LoaderType.VANILLA;
        } catch (IllegalArgumentException e) {
            throw new ProfileException(e.getMessage());
        }
        if (p.length > 2) t.loaderVersion = p[2];
        return t;
    }

    @Command(name = "list", description = "List stored profiles.")
    static class ListCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Override
        public Integer call() throws Exception {
            List<Profile> all = parent.store().list();
            if (all.isEmpty()) parent.out().println("No profiles yet. Create one with: verity profile new <name> --target 1.20.1:fabric --mod build/libs/mod.jar");
            for (Profile p : all) {
                parent.out().printf("%-24s %2d target spec(s), %2d mod entr%s%s%n", p.name, p.targets.size(), p.mods.size(),
                        p.mods.size() == 1 ? "y" : "ies", p.description != null ? "  " + p.description : "");
            }
            parent.out().println("(stored in " + parent.store().dir() + ")");
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "show", description = "Print a profile as JSON.")
    static class Show implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Parameters(paramLabel = "PROFILE")
        String name;

        @Override
        public Integer call() throws Exception {
            Profile p = parent.store().resolve(name);
            parent.out().println(Json.MAPPER.writeValueAsString(p));
            if (p.source != null) parent.out().println("// " + p.source);
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "new", description = "Create a profile.")
    static class New implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Parameters(paramLabel = "NAME")
        String name;

        @Option(names = {"-t", "--target"}, paramLabel = "MC[:LOADER[:VERSION]]", description = "e.g. 1.8.9:forge, 1.20.1:fabric, 1.21.1:neoforge, 1.21.4 (vanilla).")
        List<String> targets = new ArrayList<>();

        @Option(names = {"-s", "--select"}, paramLabel = "RANGE", description = "Add a selector for a version range, e.g. '>=1.20' (use with --loaders).")
        String select;

        @Option(names = "--loaders", split = ",", description = "Loaders for --select (comma separated).")
        List<String> loaders = new ArrayList<>();

        @Option(names = "--latest-patch", description = "With --select: only the newest patch of each minor version.")
        boolean latestPatch;

        @Option(names = {"-m", "--mod"}, paramLabel = "PATH", description = "Mod jar, folder or glob (made absolute).")
        List<String> mods = new ArrayList<>();

        @Option(names = "--memory", description = "Max heap per client in MiB (default 2048).")
        Integer memory;

        @Option(names = "--output", description = "Write the profile to this file instead of the profile store.")
        Path output;

        @Option(names = "--force", description = "Overwrite an existing profile.")
        boolean force;

        @Override
        public Integer call() throws Exception {
            Profile p = new Profile();
            p.name = name;
            for (String t : targets) p.targets.add(parseTarget(t));
            if (select != null) {
                Profile.TargetSpec s = new Profile.TargetSpec();
                s.select = new Profile.Selector();
                s.select.minecraft = select;
                s.select.latestPatchOnly = latestPatch;
                for (String l : loaders) {
                    try {
                        s.select.loaders.add(LoaderType.parse(l));
                    } catch (IllegalArgumentException e) {
                        throw new ProfileException(e.getMessage());
                    }
                }
                p.targets.add(s);
            }
            for (String m : mods) {
                p.mods.add(Profile.ModEntry.of(output != null ? m : Path.of(m).toAbsolutePath().normalize().toString()));
            }
            if (memory != null) p.jvm.memoryMb = memory;
            Path written;
            if (output != null) {
                if (Files.exists(output) && !force) throw new ProfileException(output + " exists (use --force)");
                ProfileStore.saveTo(p, output);
                written = output;
            } else {
                if (parent.store().find(name).isPresent() && !force) throw new ProfileException("Profile '" + name + "' exists (use --force)");
                written = parent.store().save(p);
            }
            parent.out().println("Wrote " + written);
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "rm", aliases = "delete", description = "Delete a stored profile.")
    static class Remove implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        public Integer call() throws Exception {
            Profile p = parent.store().find(name).orElseThrow(() -> new ProfileException("No profile named '" + name + "'"));
            parent.store().delete(p);
            parent.out().println("Deleted " + p.name);
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "import", description = "Copy a profile file into the store (relative paths are made absolute).")
    static class Import implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Parameters(paramLabel = "FILE")
        Path file;

        @Override
        public Integer call() throws Exception {
            Profile p = parent.store().importProfile(file);
            parent.out().println("Imported " + p.name + " -> " + p.source);
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "export", description = "Write a stored profile to a file.")
    static class Export implements Callable<Integer> {
        @CommandLine.ParentCommand
        ProfileCommand parent;

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Parameters(index = "1", paramLabel = "FILE")
        Path file;

        @Override
        public Integer call() throws Exception {
            Profile p = parent.store().resolve(name);
            ProfileStore.saveTo(p, file);
            parent.out().println("Exported " + p.name + " -> " + file.toAbsolutePath());
            parent.out().flush();
            return 0;
        }
    }
}

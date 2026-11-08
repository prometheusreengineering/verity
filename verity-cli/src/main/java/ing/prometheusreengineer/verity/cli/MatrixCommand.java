package ing.prometheusreengineer.verity.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import ing.prometheusreengineer.verity.core.ci.CiOs;
import ing.prometheusreengineer.verity.core.ci.CiPlan;
import ing.prometheusreengineer.verity.core.ci.CiPlanner;
import ing.prometheusreengineer.verity.core.ci.CostModel;
import ing.prometheusreengineer.verity.core.install.InstallContext;
import ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver;
import ing.prometheusreengineer.verity.core.mods.ModPlanner;
import ing.prometheusreengineer.verity.core.mods.ModResolver;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.profile.ProfileStore;
import ing.prometheusreengineer.verity.core.run.RunOptions;
import ing.prometheusreengineer.verity.core.run.RunOrchestrator;
import ing.prometheusreengineer.verity.core.run.RunReport;
import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.util.Json;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "matrix", description = {
        "Plan a CI run: how many runners, which targets each one runs and how the caches are grouped, as JSON for a GitHub "
                + "Actions matrix (the action's plan mode runs this)."})
class MatrixCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", paramLabel = "PROFILE")
    String profile;

    @Option(names = {"-o", "--only"}, split = ",", paramLabel = "FILTER", description = "Only plan matching targets.")
    List<String> only = new ArrayList<>();

    @Option(names = "--os", split = ",", defaultValue = "linux", description = "linux, windows or both (default: ${DEFAULT-VALUE}).")
    List<String> oses = new ArrayList<>();

    @Option(names = "--max-runners", defaultValue = "20", description = "Most runners to use in all (default: ${DEFAULT-VALUE}).")
    int maxRunners;

    @Option(names = "--knee-seconds", defaultValue = "15",
            description = "Only add a runner if it saves at least this many seconds (default: ${DEFAULT-VALUE}).")
    double kneeSeconds;

    @Option(names = "--max-groups", defaultValue = "6", description = "Most cache groups (default: ${DEFAULT-VALUE}).")
    int maxGroups;

    @Option(names = "--cache-budget-gb", defaultValue = "10",
            description = "Keep the caches under this size if possible; GitHub evicts above 10 GB per repository (default: ${DEFAULT-VALUE}).")
    double budgetGb;

    @Option(names = "--history", paramLabel = "DIR", description = "Earlier report.json files (searched recursively) to learn run times from.")
    Path history;

    @Option(names = "--caches", paramLabel = "FILE",
            description = "Existing caches: JSON objects with key and size_in_bytes, one per line or as an array.")
    Path caches;

    @Option(names = "--week", description = "Cache week, e.g. 2026-W45 (default: the current ISO week in UTC).")
    String week;

    @Option(names = "--profile-hash", description = "Identifies the profile in cache keys (default: SHA-256 of the profile file).")
    String profileHash;

    @Option(names = "--runner", split = ",", paramLabel = "OS=LABEL", description = "runs-on label per OS, e.g. linux=ubuntu-24.04.")
    Map<String, String> runnerLabels = new LinkedHashMap<>();

    @Override
    public Integer call() throws Exception {
        Profile p = new ProfileStore(root.verityHome()).resolve(profile);
        RunOrchestrator.validate(p);
        List<CiOs> osList = new ArrayList<>();
        for (String s : oses) {
            CiOs os = CiOs.parse(s);
            if (!osList.contains(os)) osList.add(os);
        }
        if (maxRunners < osList.size()) throw new ProfileException("--max-runners must be at least the number of OSes");
        Map<CiOs, String> runners = new EnumMap<>(CiOs.class);
        for (Map.Entry<String, String> e : runnerLabels.entrySet()) runners.put(CiOs.parse(e.getKey()), e.getValue());

        InstallContext ctx = root.context();
        RunOptions o = new RunOptions();
        o.only = only;
        List<Target> expanded = new RunOrchestrator(ctx).targets(p, o);
        List<ModResolver.ModFile> mods;
        List<String> notes = new ArrayList<>();
        try {
            mods = ModResolver.expand(p);
        } catch (ProfileException e) {
            // The plan job usually runs while the mod is still being built. Without its jar, plan with the Modrinth
            // entries only; targets the local jar would skip still get runners and are skipped when they run.
            p.mods.removeIf(m -> !m.fromModrinth());
            mods = ModResolver.expand(p);
            notes.add("Planned without the local mod jars (" + e.getMessage() + ").");
        }
        Map<Target, ModPlanner.TargetPlan> plan = ModPlanner.plan(p, expanded, mods, new ModrinthResolver(ctx.modrinth()), ctx.home());
        List<Target> runnable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Target t : expanded) {
            if (p.run.onlyTargetsWithAllMods && !plan.get(t).missingEntries().isEmpty()) skipped.add(t.id());
            else runnable.add(t);
        }

        CostModel cost = new CostModel().learn(reports(history));
        Set<String> existing = new HashSet<>();
        Map<String, Long> sizes = new HashMap<>();
        readCaches(caches, existing, sizes);
        CiPlan result = CiPlanner.plan(runnable, skipped, cost, new CiPlanner.Options(osList, maxRunners, kneeSeconds,
                maxGroups, (long) (budgetGb * 1e9), runners, profileHash != null ? profileHash : hash(profile),
                week != null ? week : currentWeek(), existing, sizes));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prepare", Map.of("include", result.prepare().stream().map(MatrixCommand::entry).toList()));
        out.put("shards", Map.of("include", result.shards().stream().map(MatrixCommand::entry).toList()));
        out.put("hasPrepare", !result.prepare().isEmpty());
        out.put("hasShards", !result.shards().isEmpty());
        String md = markdown(p.name, result);
        for (String note : notes) md += "\n" + note + "\n";
        out.put("summary", md);
        PrintWriter w = spec.commandLine().getOut();
        w.println(Json.MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out));
        w.flush();
        return VerityCli.OK;
    }

    /** A matrix entry; the action takes the whole entry as its {@code job} input. Values are strings for the YAML side. */
    static Map<String, String> entry(CiPlan.Job j) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mode", j.mode());
        m.put("os", j.os().runnerOs);
        m.put("runner", j.runner());
        m.put("group", String.valueOf(j.group()));
        m.put("key", j.key());
        m.put("name", j.name());
        m.put("targets", String.join(",", j.targets()));
        m.put("parallel", String.valueOf(j.parallel()));
        return m;
    }

    static List<RunReport> reports(Path dir) {
        List<RunReport> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(f -> f.getFileName().toString().equals("report.json")).toList()) {
                try {
                    out.add(Json.read(f, RunReport.class));
                } catch (IOException e) {
                    // A torn or foreign file only costs its timings.
                }
            }
        } catch (IOException e) {
            // Learning is optional.
        }
        return out;
    }

    /** Reads the caches API's entries (key, size_in_bytes); sizes are kept by key without the week. */
    static void readCaches(Path file, Set<String> existing, Map<String, Long> sizes) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return;
        String text = Files.readString(file, StandardCharsets.UTF_8).trim();
        List<JsonNode> nodes = new ArrayList<>();
        // gh --jq prints one value after another, however it formats them; an array works too.
        try (var values = Json.MAPPER.readerFor(JsonNode.class).<JsonNode>readValues(text)) {
            while (values.hasNext()) {
                JsonNode v = values.next();
                if (v.isArray()) v.forEach(nodes::add);
                else nodes.add(v);
            }
        }
        for (JsonNode n : nodes) {
            String key = Json.text(n, "key");
            if (key == null) continue;
            existing.add(key);
            long size = n.path("size_in_bytes").asLong(0);
            if (size > 0) sizes.merge(key.replaceFirst("-\\d{4}-W\\d{2}$", ""), size, Math::max);
        }
    }

    static String currentWeek() {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** Same as the action's: SHA-256 of the file without carriage returns, so every OS gets the same keys. */
    static String hash(String profile) throws Exception {
        Path f = Path.of(profile);
        byte[] bytes = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).replace("\r", "").getBytes(StandardCharsets.UTF_8)
                : profile.getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String markdown(String name, CiPlan plan) {
        CiPlan.Summary s = plan.summary();
        StringBuilder b = new StringBuilder("### Verity plan for ").append(name).append("\n\n");
        int targets = plan.groups().stream().mapToInt(CiPlan.Group::targets).sum();
        b.append(targets).append(" targets on ");
        List<String> parts = new ArrayList<>();
        s.runners().forEach((os, n) -> parts.add(n + " " + os.runnerOs + " runner" + (n == 1 ? "" : "s")));
        b.append(parts.isEmpty() ? "no runners" : String.join(" and ", parts));
        b.append(String.format(", in %d cache group%s. Predicted: tests %s", plan.groups().size(), plan.groups().size() == 1 ? "" : "s",
                minutes(s.predictedSeconds())));
        if (!plan.prepare().isEmpty()) {
            b.append(String.format(", after %d prepare job%s (%s)", plan.prepare().size(), plan.prepare().size() == 1 ? "" : "s",
                    minutes(s.predictedPrepareSeconds())));
        }
        b.append(s.learned() ? ", from this project's earlier run times." : ", from Verity's built-in timings (no earlier run found).")
                .append("\n\n| Group | Minecraft | Targets | Shared cache | ");
        List<CiOs> oses = plan.groups().isEmpty() ? List.of() : new ArrayList<>(plan.groups().get(0).osBytes().keySet());
        for (CiOs os : oses) b.append(os.runnerOs).append(" cache | ");
        b.append("\n|---|---|---|---|").append("---|".repeat(oses.size())).append('\n');
        for (CiPlan.Group g : plan.groups()) {
            b.append("| ").append(g.index()).append(" | ").append(g.from()).append(g.from().equals(g.to()) ? "" : " - " + g.to())
                    .append(" | ").append(g.targets()).append(" | ").append(gb(g.sharedBytes())).append(" | ");
            for (CiOs os : oses) {
                b.append(gb(g.osBytes().get(os))).append(Boolean.TRUE.equals(g.cached().get(os)) ? " (cached)" : "").append(" | ");
            }
            b.append('\n');
        }
        b.append(String.format("%nCaches: about %s in all (estimated), budget %s.%n", gb(s.cacheBytes()), gb(s.budgetBytes())));
        for (String warning : s.warnings()) b.append("\n> ").append(warning).append('\n');
        if (!s.skipped().isEmpty()) b.append("\nSkipped (a mod has no file): ").append(String.join(", ", s.skipped())).append('\n');
        return b.toString();
    }

    private static String minutes(double seconds) {
        return String.format("%.1f min", seconds / 60);
    }

    private static String gb(Long bytes) {
        return bytes == null ? "?" : String.format("%.1f GB", bytes / 1e9);
    }
}

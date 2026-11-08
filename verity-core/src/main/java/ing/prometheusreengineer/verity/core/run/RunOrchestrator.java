package ing.prometheusreengineer.verity.core.run;

import ing.prometheusreengineer.verity.core.auth.AccountService;
import ing.prometheusreengineer.verity.core.auth.LaunchAccount;
import ing.prometheusreengineer.verity.core.display.DisplayProvider;
import ing.prometheusreengineer.verity.core.display.Displays;
import ing.prometheusreengineer.verity.core.hook.HookServer;
import ing.prometheusreengineer.verity.core.install.InstallContext;
import ing.prometheusreengineer.verity.core.install.Installer;
import ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver;
import ing.prometheusreengineer.verity.core.mods.ModPlanner;
import ing.prometheusreengineer.verity.core.mods.ModResolver;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.report.Reports;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Runs every target of a profile in parallel and writes the reports. */
public final class RunOrchestrator {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final InstallContext ctx;
    private final Installer installer;
    private final AccountService accounts;

    public RunOrchestrator(InstallContext ctx) {
        this.ctx = ctx;
        this.installer = new Installer(ctx);
        this.accounts = new AccountService(ctx);
    }

    public InstallContext context() {
        return ctx;
    }

    public AccountService accounts() {
        return accounts;
    }

    /** Expands targets and applies {@code options.only}. */
    public List<Target> targets(Profile profile, RunOptions options) throws IOException, ProfileException {
        List<Target> all = new TargetExpander(TargetExpander.Catalog.of(ctx.meta(), new ModrinthResolver(ctx.modrinth()))).expand(profile);
        if (options.only == null || options.only.isEmpty()) return all;
        return all.stream().filter(t -> options.only.stream().anyMatch(t::matchesFilter)).toList();
    }

    /**
     * Validates the profile synchronously (so configuration errors surface immediately) and starts the run in
     * the background.
     */
    public RunHandle start(Profile profile, RunOptions options, RunListener listener) throws IOException, ProfileException {
        validate(profile);
        List<Target> expanded = targets(profile, options);
        if (expanded.isEmpty()) throw new ProfileException("Profile '" + profile.name + "' has no targets to run");
        List<ModResolver.ModFile> mods = ModResolver.expand(profile);
        Map<Target, ModPlanner.TargetPlan> plan = ModPlanner.plan(profile, expanded, mods, new ModrinthResolver(ctx.modrinth()), ctx.home());
        List<Target> targets = options.shard == null || options.prepareOnly ? expanded
                : options.shard.apply(expanded, t -> profile.run.onlyTargetsWithAllMods && !plan.get(t).missingEntries().isEmpty());
        if (targets.isEmpty()) listener.warning("Shard " + options.shard + " has no targets: there are fewer targets than shards");

        Profile.DisplayMode mode = options.display != null ? options.display : profile.display.mode;
        DisplayProvider display = options.displayProvider != null ? options.displayProvider : Displays.forMode(mode, ctx);
        if (!options.prepareOnly) {
            display.preflight();
            display.warnings().forEach(listener::warning);
        }
        LaunchAccount account = options.prepareOnly ? LaunchAccount.offline("Verity") : accounts.resolve(profile.account);
        if (!targets.isEmpty() && targets.stream().allMatch(t -> t.loader() == ing.prometheusreengineer.verity.core.meta.LoaderType.VANILLA)
                && profile.mods.stream().anyMatch(m -> !m.agent)) {
            listener.warning("All targets are vanilla, which cannot load mods");
        }

        Instant started = Instant.now();
        Path runDir = options.reportDir != null ? options.reportDir.toAbsolutePath()
                : ctx.home().runs().resolve(STAMP.format(started) + "-" + FileUtil.safeName(profile.name));
        Files.createDirectories(runDir);
        boolean keep = options.keepInstances != null ? options.keepInstances : profile.run.keepInstances;
        int parallel = options.maxParallel != null && options.maxParallel > 0 ? options.maxParallel
                : profile.run.maxParallel > 0 ? profile.run.maxParallel
                : ctx.settings().maxParallel > 0 ? ctx.settings().maxParallel
                : autoParallel(profile.jvm.memoryMb);

        HookServer hook = new HookServer();
        List<ClientSession> sessions = new ArrayList<>();
        Map<Target, TargetResult> skipped = new LinkedHashMap<>();
        InstallBarrier barrier = new InstallBarrier(targets.stream()
                .filter(t -> !(profile.run.onlyTargetsWithAllMods && !plan.get(t).missingEntries().isEmpty())).toList());
        for (Target t : targets) {
            ModPlanner.TargetPlan tp = plan.get(t);
            if (profile.run.onlyTargetsWithAllMods && !tp.missingEntries().isEmpty()) {
                skipped.put(t, skippedResult(t, tp));
                continue;
            }
            Path instance = ctx.home().instances().resolve(FileUtil.safeName(profile.name)).resolve(t.id());
            sessions.add(new ClientSession(t, profile, installer, hook, display, account, tp.paths(), tp.agentPaths(),
                    tp.downloads(), instance, runDir, !keep, listener, barrier));
        }
        listener.runStarted(profile.name, targets, runDir);
        for (Target t : targets) {
            TargetResult sk = skipped.get(t);
            listener.targetStatus(t, sk != null ? Status.SKIPPED : Status.PENDING, sk != null ? sk.message : null);
        }

        Semaphore permits = new Semaphore(parallel);
        CompletableFuture<RunReport> future = new CompletableFuture<>();
        Thread.ofPlatform().name("verity-run").daemon(false).start(() -> {
            List<CompletableFuture<TargetResult>> results = new ArrayList<>();
            Map<Target, ClientSession> byTarget = new LinkedHashMap<>();
            for (ClientSession s : sessions) byTarget.put(s.target(), s);
            for (Target t : targets) {
                ClientSession s = byTarget.get(t);
                if (s == null) {
                    results.add(CompletableFuture.completedFuture(skipped.get(t)));
                    continue;
                }
                CompletableFuture<TargetResult> f = new CompletableFuture<>();
                Thread.ofVirtual().name("verity-" + t.id()).start(() -> {
                    // run() handles Exceptions itself; anything escaping (an Error, a throwing listener) must still
                    // complete the future, or the run waits for this target forever.
                    try {
                        f.complete(s.run(permits, options.prepareOnly));
                    } catch (Throwable e) {
                        f.complete(s.aborted(e));
                    }
                });
                results.add(f);
            }
            try {
                RunReport report = new RunReport();
                report.profile = profile.name;
                report.startedAt = started;
                report.runDir = runDir.toString();
                report.prepareOnly = options.prepareOnly;
                report.host = host();
                for (CompletableFuture<TargetResult> f : results) report.results.add(f.join());
                report.finishedAt = Instant.now();
                try {
                    hook.close();
                } catch (IOException ignored) {
                    // closing
                }
                try {
                    Reports.writeAll(report, runDir);
                } catch (IOException e) {
                    listener.warning("Could not write reports: " + e.getMessage());
                }
                listener.runFinished(report);
                future.complete(report);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        RunHandle handle = new RunHandle(future, sessions);
        // On Ctrl+C / SIGTERM: stop every client and give sessions time to kill their game processes and displays.
        Thread hookThread = new Thread(() -> {
            handle.cancel();
            try {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort while the JVM shuts down
            }
        }, "verity-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hookThread);
            future.whenComplete((r, e) -> {
                try {
                    Runtime.getRuntime().removeShutdownHook(hookThread);
                } catch (IllegalStateException ignored) {
                    // already shutting down
                }
            });
        } catch (IllegalStateException ignored) {
            // JVM shutting down
        }
        return handle;
    }

    static TargetResult skippedResult(Target t, ModPlanner.TargetPlan tp) {
        TargetResult r = new TargetResult();
        r.id = t.id();
        r.minecraft = t.minecraft();
        r.loader = t.loader().id();
        r.loaderVersion = t.loaderVersion();
        r.status = Status.SKIPPED;
        r.message = "No file for " + String.join(", ", tp.missingEntries());
        r.mods = tp.included().stream().map(ModPlanner.PlannedMod::fileName).toList();
        return r;
    }

    public static void validate(Profile p) throws ProfileException {
        for (Profile.ModEntry m : p.mods) {
            boolean hasPath = m.path != null && !m.path.isBlank();
            if (hasPath == m.fromModrinth()) {
                throw new ProfileException("Each mod entry needs exactly one of \"path\" or \"modrinth\""
                        + (hasPath ? " (both set: " + m.path + ", " + m.modrinth + ")" : ""));
            }
            if (m.agent && m.fromModrinth()) {
                throw new ProfileException("Java agents must be local jars (\"agent\" with \"modrinth\": " + m.modrinth + ")");
            }
            if (m.channel != null && !m.channel.isBlank() && !m.channel.matches("(?i)release|beta|alpha")) {
                throw new ProfileException("Mod channel must be release, beta or alpha (got '" + m.channel + "')");
            }
        }
        if (p.name == null || p.name.isBlank()) throw new ProfileException("Profile needs a name");
        if (p.jvm.memoryMb < 512) throw new ProfileException("jvm.memoryMb must be at least 512");
        if (p.test.timeoutSec < 10 || p.test.startupTimeoutSec < 10) throw new ProfileException("Timeouts must be at least 10 seconds");
        if (p.display.width < 320 || p.display.height < 240) throw new ProfileException("Display size must be at least 320x240");
    }

    /** Half the CPU cores, bounded by how many clients fit into ~75% of physical memory. */
    public static int autoParallel(int memoryMb) {
        int cpu = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        long totalMb = 8192;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            totalMb = os.getTotalMemorySize() / (1024 * 1024);
        }
        long perClient = memoryMb + 768L; // heap + JVM/native/Mesa overhead
        int byMem = (int) Math.max(1, (totalMb * 3 / 4) / perClient);
        return Math.max(1, Math.min(cpu, byMem));
    }

    private static String host() {
        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        try {
            return InetAddress.getLocalHost().getHostName() + " (" + os + ")";
        } catch (IOException e) {
            return os;
        }
    }
}

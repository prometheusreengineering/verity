package dev.verity.core.run;

import dev.verity.core.auth.AccountService;
import dev.verity.core.auth.LaunchAccount;
import dev.verity.core.display.DisplayProvider;
import dev.verity.core.display.Displays;
import dev.verity.core.hook.HookServer;
import dev.verity.core.install.InstallContext;
import dev.verity.core.install.Installer;
import dev.verity.core.mods.ModResolver;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileException;
import dev.verity.core.report.Reports;
import dev.verity.core.util.FileUtil;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
        List<Target> all = new TargetExpander(TargetExpander.Catalog.of(ctx.meta())).expand(profile);
        if (options.only == null || options.only.isEmpty()) return all;
        return all.stream().filter(t -> options.only.stream().anyMatch(t::matchesFilter)).toList();
    }

    /**
     * Validates the profile synchronously (so configuration errors surface immediately) and starts the run in
     * the background.
     */
    public RunHandle start(Profile profile, RunOptions options, RunListener listener) throws IOException, ProfileException {
        validate(profile);
        List<Target> targets = targets(profile, options);
        if (targets.isEmpty()) throw new ProfileException("Profile '" + profile.name + "' has no targets to run");
        List<ModResolver.ModFile> mods = ModResolver.expand(profile);

        Profile.DisplayMode mode = options.display != null ? options.display : profile.display.mode;
        DisplayProvider display = options.displayProvider != null ? options.displayProvider : Displays.forMode(mode, ctx);
        if (!options.prepareOnly) {
            display.preflight();
            display.warnings().forEach(listener::warning);
        }
        LaunchAccount account = options.prepareOnly ? LaunchAccount.offline("Verity") : accounts.resolve(profile.account);
        if (targets.stream().allMatch(t -> t.loader() == dev.verity.core.meta.LoaderType.VANILLA) && !mods.isEmpty()) {
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
        for (Target t : targets) {
            Path instance = ctx.home().instances().resolve(FileUtil.safeName(profile.name)).resolve(t.id());
            sessions.add(new ClientSession(t, profile, installer, hook, display, account, ModResolver.assigned(mods, t), instance, runDir,
                    !keep, listener));
        }
        listener.runStarted(profile.name, targets, runDir);
        for (ClientSession s : sessions) listener.targetStatus(s.target(), Status.PENDING, null);

        Semaphore permits = new Semaphore(parallel);
        CompletableFuture<RunReport> future = new CompletableFuture<>();
        Thread.ofPlatform().name("verity-run").daemon(false).start(() -> {
            List<CompletableFuture<TargetResult>> results = new ArrayList<>();
            for (ClientSession s : sessions) {
                CompletableFuture<TargetResult> f = new CompletableFuture<>();
                Thread.ofVirtual().name("verity-" + s.target().id()).start(() -> f.complete(s.run(permits, options.prepareOnly)));
                results.add(f);
            }
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

    public static void validate(Profile p) throws ProfileException {
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

package dev.verity.core.run;

import dev.verity.api.Verity;
import dev.verity.core.auth.LaunchAccount;
import dev.verity.core.display.DisplayProvider;
import dev.verity.core.display.DisplaySession;
import dev.verity.core.display.GameProcess;
import dev.verity.core.display.MesaGl;
import dev.verity.core.display.ProcessSpec;
import dev.verity.core.display.Win32HiddenDesktop;
import dev.verity.core.hook.HookListener;
import dev.verity.core.hook.HookServer;
import dev.verity.core.install.InstalledVersion;
import dev.verity.core.install.Installer;
import dev.verity.core.install.Libraries;
import dev.verity.core.launch.InstancePreparer;
import dev.verity.core.launch.LaunchCommandBuilder;
import dev.verity.core.launch.LaunchRequest;
import dev.verity.core.launch.LogParser;
import dev.verity.core.meta.LoaderType;
import dev.verity.core.meta.Rule;
import dev.verity.core.profile.Profile;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Os;
import dev.verity.core.util.Progress;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares, launches and supervises one client, and turns what happened into a {@link TargetResult}. */
public final class ClientSession {
    private static final int LOG_TAIL = 80;

    private final Target target;
    private final Profile profile;
    private final Installer installer;
    private final HookServer hook;
    private final DisplayProvider display;
    private final LaunchAccount account;
    private final List<Path> mods;
    private final List<dev.verity.core.net.Downloader.Artifact> downloads;
    private final Path instanceRoot;
    private final Path reportDir;
    private final Path runDir;
    private final boolean freshInstance;
    private final RunListener listener;
    private final InstallBarrier barrier;
    private final AtomicBoolean installReported = new AtomicBoolean();

    private final TargetResult result = new TargetResult();
    private final List<CheckResult> checks = Collections.synchronizedList(new ArrayList<>());
    private final List<String> verityLog = Collections.synchronizedList(new ArrayList<>());
    /** Mesa was wanted but failed to install, so the client runs on the real GPU driver. */
    private volatile boolean mesaFallback;
    private final Deque<String> tail = new ArrayDeque<>();
    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    /** Minecraft 26.3+ graphics backends that failed to start, from the game log. */
    private final java.util.Set<String> failedBackends = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean backendUp;
    private volatile Status status = Status.PENDING;
    private volatile GameProcess process;

    ClientSession(Target target, Profile profile, Installer installer, HookServer hook, DisplayProvider display, LaunchAccount account,
                  List<Path> mods, List<dev.verity.core.net.Downloader.Artifact> downloads, Path instanceRoot, Path runDir,
                  boolean freshInstance, RunListener listener, InstallBarrier barrier) {
        this.barrier = barrier;
        this.target = target;
        this.profile = profile;
        this.installer = installer;
        this.hook = hook;
        this.display = display;
        this.account = account;
        this.mods = mods;
        this.downloads = downloads;
        this.instanceRoot = instanceRoot;
        this.runDir = runDir;
        this.reportDir = runDir.resolve("targets").resolve(target.id());
        this.freshInstance = freshInstance;
        this.listener = listener;
        result.id = target.id();
        result.minecraft = target.minecraft();
        result.loader = target.loader().id();
        result.loaderVersion = target.loaderVersion();
        result.mods = mods.stream().map(p -> p.getFileName().toString()).toList();
        result.status = Status.PENDING;
    }

    public Target target() {
        return target;
    }

    public Status status() {
        return status;
    }

    /** Requests cancellation; the session stops its client and reports CANCELLED. */
    public void cancel() {
        outcome.complete("cancel");
    }

    private void setStatus(Status s, String message) {
        status = s;
        result.status = s;
        if (message != null) result.message = message;
        listener.targetStatus(target, s, message);
    }

    private Progress progress() {
        return new Progress() {
            @Override
            public void stage(String description) {
                listener.targetProgress(target, description, 0, 0);
            }

            @Override
            public void progress(long done, long total) {
                listener.targetProgress(target, null, done, total);
            }
        };
    }

    /**
     * Runs to completion.
     *
     * @param launchPermits limits how many clients run at once; held only while the game process lives
     */
    TargetResult run(Semaphore launchPermits, boolean prepareOnly) {
        long start = System.currentTimeMillis();
        try {
            Files.createDirectories(reportDir);
            if (outcome.isDone()) return finish(Status.CANCELLED, "Cancelled", start);

            setStatus(Status.PREPARING, "Installing");
            String loaderVersion = null;
            if (target.loader() != LoaderType.VANILLA) {
                loaderVersion = installer.context().meta().loader(target.loader()).resolve(target.minecraft(), target.loaderVersion());
                result.loaderVersion = loaderVersion;
            }
            if (!downloads.isEmpty()) {
                progress().stage("Downloading " + downloads.size() + " mod" + (downloads.size() == 1 ? "" : "s"));
                installer.context().downloader().downloadAll(downloads, progress());
            }
            InstalledVersion version = installer.install(target.minecraft(), target.loader(), loaderVersion, progress());
            installDone();
            Path java = installer.context().java().javaFor(version, progress());
            if (Os.current() == Os.WINDOWS && display instanceof Win32HiddenDesktop && Win32HiddenDesktop.usesMesa(profile.display)) {
                try {
                    java = new MesaGl(installer.context()).javaWithMesa(java, progress());
                } catch (Exception e) {
                    if (profile.display.softwareGl == Profile.SoftwareGl.ON) throw e;
                    // auto: Mesa is a convenience for GPU-less machines; carry on with the real driver.
                    String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                    verityLog.add("Mesa software OpenGL unavailable (" + reason + "); using the GPU driver");
                    listener.targetLog(target, "[verity] Mesa software OpenGL unavailable (" + reason + "); using the GPU driver");
                    Files.writeString(reportDir.resolve("mesa-error.txt"), stackTrace(e));
                    mesaFallback = true;
                }
            }
            result.java = java.toString();
            if (prepareOnly) return finish(Status.PREPARED, "Installed " + version.id(), start);

            if (barrier.shared(target)) {
                setStatus(Status.QUEUED, "Waiting for other Minecraft " + target.minecraft() + " installs");
                barrier.await(target);
            }
            setStatus(Status.QUEUED, "Waiting for a free slot");
            launchPermits.acquire();
            try {
                if (outcome.isDone()) return finish(Status.CANCELLED, "Cancelled", start);
                return launchAndSupervise(version, java, start);
            } finally {
                launchPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(Status.CANCELLED, "Interrupted", start);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            try {
                Files.writeString(reportDir.resolve("error.txt"), stackTrace(e));
            } catch (IOException ignored) {
                // best effort
            }
            return finish(Status.ERROR, msg, start);
        } finally {
            // A session that ends before or during its install must not hold back the others.
            installDone();
        }
    }

    private void installDone() {
        if (installReported.compareAndSet(false, true)) barrier.installed(target);
    }

    private TargetResult launchAndSupervise(InstalledVersion version, Path java, long start) throws Exception {
        setStatus(Status.LAUNCHING, "Preparing instance");
        Rule.Environment env = Rule.Environment.current();
        List<Libraries.Resolved> libs = Libraries.resolve(version.version(), env, installer.context().home().libraries());
        InstancePreparer.Layout layout = InstancePreparer.prepare(instanceRoot, freshInstance, profile, mods, version, libs, installer.game());
        result.instanceDir = layout.root().toString();

        List<Path> classpath = new ArrayList<>();
        for (Libraries.Resolved r : libs) if (!r.natives()) classpath.add(r.path());
        classpath.add(version.clientJar());

        HookListener hookListener = new SessionHookListener();
        String token = hook.register(hookListener);
        // Unregistered on every path, including a display or launch failure, so the listener (and this session) can't leak.
        try {
            Map<String, String> sysProps = new LinkedHashMap<>();
            sysProps.put(Verity.PROP_PORT, String.valueOf(hook.port()));
            sysProps.put(Verity.PROP_TOKEN, token);
            sysProps.put(Verity.PROP_TARGET, target.id());
            if (profile.run.isolateHome) sysProps.put("user.home", layout.homeDir().toString());

            boolean virtualAssets = installer.game().isVirtualAssets(version.version());
            Path assetsRoot = installer.context().home().assets();
            Path gameAssets = virtualAssets && version.version().assetIndex != null
                    ? installer.game().virtualAssetsDir(version.version().assetIndex.id) : assetsRoot;

            LaunchRequest req = new LaunchRequest(version, java, layout.gameDir(), layout.nativesDir(),
                    installer.context().home().libraries(), assetsRoot, gameAssets, installer.game().loggingConfig(version.version()),
                    classpath, account, profile.jvm.memoryMb, profile.display.width, profile.display.height,
                    profile.jvm.args, profile.game.args, sysProps);
            List<String> cmd = LaunchCommandBuilder.build(req, env);
            Files.writeString(reportDir.resolve("command.txt"), LaunchCommandBuilder.describe(cmd, account.accessToken()));

            Path output = reportDir.resolve("output.log");
            Files.deleteIfExists(output);
            Files.createFile(output);
            result.logFile = runDir.relativize(output).toString().replace('\\', '/');

            try (DisplaySession ds = display.open(target.id(), profile.display)) {
                String displayDescription = mesaFallback
                        ? ds.description().replace(" (Mesa llvmpipe)", "") + " (GPU driver, Mesa unavailable)"
                        : ds.description();
                result.display = displayDescription;
                Map<String, String> procEnv = new LinkedHashMap<>(ds.environment());
                if (profile.run.isolateHome) sandboxEnvironment(procEnv, layout.homeDir());
                procEnv.putAll(profile.game.env);

                long launchedAt = System.currentTimeMillis();
                GameProcess p = ds.start(new ProcessSpec(cmd, layout.gameDir(), procEnv, output));
                process = p;
                setStatus(Status.RUNNING, "Running (pid " + p.pid() + ", " + displayDescription + ")");
                Thread tailer = Thread.ofVirtual().name("verity-tail-" + target.id()).start(() -> tailOutput(output, p));

                p.onExit().thenAccept(code -> outcome.complete("exit"));
                int smokeSec = target.loader() == LoaderType.VANILLA ? profile.test.vanillaSmokeSec : profile.test.smokeSec;
                // With a smoke test, a client that never connects passes by surviving, so the connect timeout doesn't apply.
                if (smokeSec <= 0) {
                    CompletableFuture.delayedExecutor(profile.test.startupTimeoutSec, TimeUnit.SECONDS)
                            .execute(() -> {
                                if (!connected.get()) outcome.complete("startup-timeout");
                            });
                }
                CompletableFuture.delayedExecutor(profile.test.timeoutSec, TimeUnit.SECONDS).execute(() -> outcome.complete("timeout"));
                if (smokeSec > 0) {
                    CompletableFuture.delayedExecutor(smokeSec, TimeUnit.SECONDS).execute(() -> {
                        if (!connected.get() && p.isAlive()) outcome.complete("smoke");
                    });
                }

                String why = outcome.get();
                Status finalStatus;
                String message;
                switch (why) {
                    case "finish" -> {
                        p.stop(Duration.ofSeconds(Math.max(1, profile.test.shutdownGraceSec)));
                        long failed = checks.stream().filter(c -> !c.passed()).count();
                        finalStatus = failed > 0 ? Status.FAILED : Status.PASSED;
                        message = failed > 0 ? failed + " of " + checks.size() + " checks failed"
                                : checks.isEmpty() ? "Finished" : "All " + checks.size() + " checks passed";
                    }
                    case "exit" -> {
                        finalStatus = null;
                        message = null;
                    }
                    case "smoke" -> {
                        p.stop(Duration.ofSeconds(Math.max(1, profile.test.shutdownGraceSec)));
                        finalStatus = Status.PASSED;
                        message = "Ran " + smokeSec + "s without crashing (" + (target.loader() == LoaderType.VANILLA ? "vanilla smoke test)"
                                : "smoke test with " + mods.size() + " mod" + (mods.size() == 1 ? "" : "s") + ")");
                    }
                    case "no-backend" -> {
                        // The game sits on an error dialog forever after this, so don't wait for a timeout.
                        p.stop(Duration.ofSeconds(3));
                        finalStatus = Status.CRASHED;
                        message = "Minecraft could not start any graphics backend (" + String.join(" and ",
                                new java.util.TreeSet<>(failedBackends)) + " failed; see the game log)";
                    }
                    case "startup-timeout" -> {
                        p.stop(Duration.ofSeconds(3));
                        finalStatus = Status.TIMED_OUT;
                        message = "The mod did not connect within " + profile.test.startupTimeoutSec
                                + "s (did the game load it, and does it call verity-api?)";
                    }
                    case "timeout" -> {
                        p.stop(Duration.ofSeconds(3));
                        finalStatus = Status.TIMED_OUT;
                        message = "Did not call Verity.finish() within " + profile.test.timeoutSec + "s";
                    }
                    default -> {
                        p.stop(Duration.ofSeconds(3));
                        finalStatus = Status.CANCELLED;
                        message = "Cancelled";
                    }
                }
                Integer exit = null;
                try {
                    exit = p.onExit().get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    p.stop(Duration.ZERO);
                }
                result.exitCode = exit;
                tailer.join(Duration.ofSeconds(5));
                hook.unregister(token);

                List<Path> crashes = collectCrashReports(layout, launchedAt);
                if (finalStatus == null) {
                    long failed = checks.stream().filter(c -> !c.passed()).count();
                    if (!crashes.isEmpty() || (exit != null && exit != 0)) {
                        finalStatus = Status.CRASHED;
                        message = "Game exited with code " + exit + (crashes.isEmpty() ? "" : " (" + crashes.size() + " crash report"
                                + (crashes.size() > 1 ? "s" : "") + ")") + crashSummary(crashes);
                    } else {
                        finalStatus = Status.FAILED;
                        message = "Game exited before calling Verity.finish()" + (failed > 0 ? "; " + failed + " checks failed" : "");
                    }
                }
                copyGameLog(layout);
                return finish(finalStatus, message, start);
            }
        } finally {
            hook.unregister(token);
        }
    }

    private static void sandboxEnvironment(Map<String, String> env, Path home) throws IOException {
        if (Os.current() == Os.WINDOWS) {
            Path roaming = home.resolve("AppData").resolve("Roaming");
            Files.createDirectories(roaming);
            env.put("APPDATA", roaming.toString());
        } else {
            String realHome = System.getProperty("user.home");
            if (System.getenv("XAUTHORITY") == null && realHome != null) {
                Path xauth = Path.of(realHome, ".Xauthority");
                if (Files.exists(xauth)) env.put("XAUTHORITY", xauth.toString());
            }
            env.put("HOME", home.toString());
        }
    }

    private void tailOutput(Path file, GameProcess p) {
        LogParser parser = new LogParser();
        Path console = reportDir.resolve("console.log");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             BufferedWriter out = Files.newBufferedWriter(console, StandardCharsets.UTF_8)) {
            // Split on newline bytes and decode whole lines: a UTF-8 character can straddle two reads, and 0x0A
            // never occurs inside a multi-byte sequence.
            java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            boolean exited = false;
            while (true) {
                int n = raf.read(buf);
                if (n > 0) {
                    int from = 0;
                    for (int i = 0; i < n; i++) {
                        if (buf[i] != '\n') continue;
                        partial.write(buf, from, i - from);
                        String line = partial.toString(StandardCharsets.UTF_8).replace("\r", "");
                        partial.reset();
                        from = i + 1;
                        for (String l : parser.accept(line)) emit(l, out);
                    }
                    partial.write(buf, from, n - from);
                    continue;
                }
                if (exited) break;
                if (!p.isAlive()) exited = true; // one more pass to drain
                else Thread.sleep(150);
            }
            if (partial.size() > 0) for (String l : parser.accept(partial.toString(StandardCharsets.UTF_8))) emit(l, out);
        } catch (IOException | InterruptedException ignored) {
            // tailing is best effort
        }
    }

    private static final java.util.regex.Pattern BACKEND_FAILED = java.util.regex.Pattern.compile("Failed to create backend (\\w+)");
    private static final java.util.Set<String> ALL_BACKENDS = java.util.Set.of("OpenGL", "Vulkan");

    /**
     * For 26.3+ logs: the backend named by a "Failed to create backend X" line, "" for "Using graphics backend", else
     * null. The game tries each backend in turn and, when none starts, shows a dialog and never exits.
     */
    static String backendEvent(String line) {
        if (line.contains("Using graphics backend ")) return "";
        java.util.regex.Matcher m = BACKEND_FAILED.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private void trackBackends(String line) {
        String ev = backendEvent(line);
        if (ev == null) return;
        if (ev.isEmpty()) {
            backendUp = true;
            return;
        }
        failedBackends.add(ev);
        if (!backendUp && failedBackends.containsAll(ALL_BACKENDS)) outcome.complete("no-backend");
    }

    private void emit(String line, BufferedWriter out) throws IOException {
        trackBackends(line);
        out.write(line);
        out.newLine();
        out.flush();
        synchronized (tail) {
            tail.addLast(line);
            while (tail.size() > LOG_TAIL) tail.removeFirst();
        }
        listener.targetLog(target, line);
    }

    /** Crash reports and JVM error logs written since {@code since}; kept instances still hold older ones. */
    private List<Path> collectCrashReports(InstancePreparer.Layout layout, long since) {
        List<Path> found = new ArrayList<>();
        try {
            Path crashDir = layout.gameDir().resolve("crash-reports");
            if (Files.isDirectory(crashDir)) {
                try (var s = Files.list(crashDir)) {
                    found.addAll(s.filter(f -> f.toString().endsWith(".txt") && writtenSince(f, since)).toList());
                }
            }
            for (Path dir : List.of(layout.gameDir(), layout.root())) {
                try (var s = Files.list(dir)) {
                    found.addAll(s.filter(f -> f.getFileName().toString().startsWith("hs_err_pid") && writtenSince(f, since)).toList());
                }
            }
            Path dst = reportDir.resolve("crash-reports");
            for (Path f : found) {
                Files.createDirectories(dst);
                Path copy = dst.resolve(f.getFileName());
                Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING);
                result.crashReports.add(runDir.relativize(copy).toString().replace('\\', '/'));
            }
        } catch (IOException ignored) {
            // best effort
        }
        return found;
    }

    static boolean writtenSince(Path f, long since) {
        try {
            // A little slack for coarse file-system timestamps.
            return Files.getLastModifiedTime(f).toMillis() >= since - 2000;
        } catch (IOException e) {
            return false;
        }
    }

    /** First "Description:" of a vanilla crash report plus the exception line below it. */
    static String crashSummary(List<Path> crashes) {
        for (Path c : crashes) {
            try {
                List<String> lines = Files.readAllLines(c, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).startsWith("Description:")) continue;
                    String cause = i + 2 < lines.size() ? lines.get(i + 2).trim() : "";
                    return ": " + lines.get(i).substring("Description:".length()).trim() + (cause.isEmpty() ? "" : " - " + cause);
                }
            } catch (IOException ignored) {
                // ignore unreadable reports
            }
        }
        return "";
    }

    private void copyGameLog(InstancePreparer.Layout layout) {
        Path latest = layout.gameDir().resolve("logs").resolve("latest.log");
        try {
            if (Files.isRegularFile(latest)) Files.copy(latest, reportDir.resolve("latest.log"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private TargetResult finish(Status status, String message, long start) {
        result.durationMs = System.currentTimeMillis() - start;
        result.checks = new ArrayList<>(checks);
        result.verityLog = new ArrayList<>(verityLog);
        synchronized (tail) {
            result.logTail = new ArrayList<>(tail);
        }
        setStatus(status, message);
        return result;
    }

    /** Result for a run that died on something {@link #run} doesn't handle; doesn't trust the listener. */
    TargetResult aborted(Throwable e) {
        status = Status.ERROR;
        result.status = Status.ERROR;
        result.message = "Internal error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        try {
            Files.createDirectories(reportDir);
            Files.writeString(reportDir.resolve("error.txt"), stackTrace(e));
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            listener.targetStatus(target, Status.ERROR, result.message);
        } catch (Throwable ignored) {
            // the listener may be what failed
        }
        return result;
    }

    private static String stackTrace(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private final class SessionHookListener implements HookListener {
        @Override
        public void onConnected(String javaVersion) {
            connected.set(true);
            listener.targetLog(target, "[verity] mod connected (Java " + javaVersion + ")");
        }

        @Override
        public void onPass(String name) {
            add(new CheckResult(name, true, null, null, System.currentTimeMillis()));
        }

        @Override
        public void onFail(String name, String message, String detail) {
            add(new CheckResult(name, false, message, detail, System.currentTimeMillis()));
        }

        private void add(CheckResult c) {
            checks.add(c);
            listener.targetCheck(target, c);
        }

        @Override
        public void onLog(String message) {
            verityLog.add(message);
            listener.targetLog(target, "[verity] " + message);
        }

        @Override
        public void onAttach(String name, byte[] data) {
            try {
                Path dir = reportDir.resolve("attachments");
                Files.createDirectories(dir);
                Path f = dir.resolve(FileUtil.safeName(name));
                Files.write(f, data);
                synchronized (result) {
                    result.attachments.add(runDir.relativize(f).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                verityLog.add("attachment " + name + " could not be saved: " + e.getMessage());
            }
        }

        @Override
        public void onFinish() {
            outcome.complete("finish");
        }
    }
}

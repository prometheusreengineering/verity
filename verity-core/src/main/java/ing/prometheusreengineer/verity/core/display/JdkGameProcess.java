package ing.prometheusreengineer.verity.core.display;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** {@link GameProcess} backed by {@link ProcessBuilder}; output goes straight to the spec's file. */
public final class JdkGameProcess implements GameProcess {
    private final Process process;
    private final CompletableFuture<Integer> exit;

    private JdkGameProcess(Process process) {
        this.process = process;
        this.exit = process.onExit().thenApply(Process::exitValue);
    }

    public static JdkGameProcess start(ProcessSpec spec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command())
                .directory(spec.workDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.appendTo(spec.outputFile().toFile()))
                .redirectInput(Redirect.PIPE);
        // The game runs the code under test, which must not see Verity's credentials.
        pb.environment().keySet().removeIf(GameEnvironment::isSecret);
        pb.environment().putAll(spec.environment());
        Process p = pb.start();
        p.getOutputStream().close();
        return new JdkGameProcess(p);
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public CompletableFuture<Integer> onExit() {
        return exit;
    }

    @Override
    public void stop(Duration grace) {
        stopTree(process.toHandle(), grace);
    }

    /** Terminates a process tree, children first. */
    public static void stopTree(ProcessHandle root, Duration grace) {
        List<ProcessHandle> children = root.descendants().toList();
        root.destroy();
        children.forEach(ProcessHandle::destroy);
        try {
            root.onExit().get(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // fall through to forcible kill
        }
        if (root.isAlive()) root.destroyForcibly();
        for (ProcessHandle c : children) if (c.isAlive()) c.destroyForcibly();
    }
}

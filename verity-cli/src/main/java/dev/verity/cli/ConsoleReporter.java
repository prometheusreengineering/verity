package dev.verity.cli;

import dev.verity.core.run.CheckResult;
import dev.verity.core.run.RunListener;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.Target;
import dev.verity.core.run.TargetResult;
import dev.verity.core.report.Reports;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import picocli.CommandLine.Help.Ansi;

/** Line-oriented progress output that reads well both in terminals and CI logs. */
final class ConsoleReporter implements RunListener {
    private final PrintWriter out;
    private final Ansi ansi;
    private final boolean streamLogs;
    private final Map<String, String> lastStage = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProgress = new ConcurrentHashMap<>();
    private int width = 12;

    ConsoleReporter(PrintWriter out, Ansi ansi, boolean streamLogs) {
        this.out = out;
        this.ansi = ansi;
        this.streamLogs = streamLogs;
    }

    private synchronized void line(String targetId, String text) {
        out.println(ansi.string("@|faint [" + pad(targetId) + "]|@ ") + text);
        out.flush();
    }

    private String pad(String s) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    @Override
    public void runStarted(String profile, List<Target> targets, Path runDir) {
        width = targets.stream().mapToInt(t -> t.id().length()).max().orElse(12);
        out.println(ansi.string("@|bold Verity|@ running profile @|bold " + profile + "|@ on " + targets.size() + " target"
                + (targets.size() == 1 ? "" : "s") + ": " + String.join(", ", targets.stream().map(Target::id).toList())));
        out.println(ansi.string("@|faint Reports: " + runDir + "|@"));
        out.flush();
    }

    @Override
    public void warning(String message) {
        synchronized (this) {
            out.println(ansi.string("@|yellow Warning:|@ " + message));
            out.flush();
        }
    }

    @Override
    public void targetStatus(Target target, Status status, String message) {
        if (status == Status.PENDING) return;
        line(target.id(), colored(status) + (message != null ? " " + message : ""));
    }

    @Override
    public void targetProgress(Target target, String stage, long done, long total) {
        if (stage != null) {
            if (!stage.equals(lastStage.put(target.id(), stage))) line(target.id(), ansi.string("@|faint " + stage + "|@"));
            return;
        }
        // Throttle numeric progress to roughly every 10 seconds per target.
        long now = System.currentTimeMillis();
        Long prev = lastProgress.get(target.id());
        if (total > 50 && done < total && (prev == null || now - prev > 10_000)) {
            lastProgress.put(target.id(), now);
            line(target.id(), ansi.string("@|faint   " + done + "/" + total + "|@"));
        }
    }

    @Override
    public void targetLog(Target target, String line) {
        if (streamLogs || line.startsWith("[verity]")) line(target.id(), streamLogs ? line : ansi.string("@|cyan " + line + "|@"));
    }

    @Override
    public void targetCheck(Target target, CheckResult c) {
        line(target.id(), c.passed() ? ansi.string("@|green ✔|@ " + c.name())
                : ansi.string("@|red ✘ " + c.name() + "|@" + (c.message() != null ? ": " + c.message() : "")));
    }

    @Override
    public void runFinished(RunReport report) {
        out.println();
        out.println(ansi.string("@|bold Results|@"));
        for (TargetResult r : report.results) {
            out.println("  " + colored(r.status) + " " + pad(r.id) + "  " + Duration.ofMillis(r.durationMs).toSeconds() + "s"
                    + (r.message != null ? "  " + r.message : ""));
        }
        long total = report.results.size();
        out.println();
        out.println(ansi.string((report.success() ? "@|bold,green " : "@|bold,red ") + report.passed() + "/" + total + " passed|@"));
        Path dir = Path.of(report.runDir);
        out.println(ansi.string("@|faint HTML: " + dir.resolve(Reports.HTML) + "|@"));
        out.println(ansi.string("@|faint JUnit: " + dir.resolve(Reports.JUNIT) + "|@"));
        out.flush();
    }

    String colored(Status s) {
        String name = String.format("%-9s", s.name());
        return switch (s) {
            case PASSED, PREPARED -> ansi.string("@|bold,green " + name + "|@");
            case FAILED, CRASHED, ERROR -> ansi.string("@|bold,red " + name + "|@");
            case TIMED_OUT, CANCELLED -> ansi.string("@|bold,yellow " + name + "|@");
            default -> ansi.string("@|blue " + name + "|@");
        };
    }
}

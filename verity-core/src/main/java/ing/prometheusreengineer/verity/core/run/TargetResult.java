package ing.prometheusreengineer.verity.core.run;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one client, serialized into report.json. */
public class TargetResult {
    public String id;
    public String minecraft;
    public String loader;
    public String loaderVersion;
    public Status status;
    public String message;
    public long durationMs;
    /** From launching the game to the end, without installing or waiting for a slot; null if it never launched. */
    public Long runMs;
    public Integer exitCode;
    public String java;
    public String display;
    public List<String> mods = new ArrayList<>();
    public List<CheckResult> checks = new ArrayList<>();
    /** Lines the mod logged through {@code Verity.log}. */
    public List<String> verityLog = new ArrayList<>();
    /** Last lines of the game output. */
    public List<String> logTail = new ArrayList<>();
    /** Paths relative to the run directory. */
    public List<String> attachments = new ArrayList<>();
    public List<String> crashReports = new ArrayList<>();
    public String logFile;
    public String instanceDir;

    public boolean success() {
        return status != null && status.success();
    }
}

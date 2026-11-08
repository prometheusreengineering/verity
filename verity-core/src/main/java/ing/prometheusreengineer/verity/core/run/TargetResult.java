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
    /**
     * Set when the client was run again after a native crash ({@code test.retryNativeCrashes}): how the first attempt
     * ended. Its logs are in {@code targets/<id>/attempt-1/}; everything else here is from the retry.
     */
    public String retriedAfter;
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

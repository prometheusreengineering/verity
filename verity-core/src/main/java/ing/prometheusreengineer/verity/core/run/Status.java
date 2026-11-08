package ing.prometheusreengineer.verity.core.run;

/** Lifecycle of one client. */
public enum Status {
    PENDING, PREPARING, QUEUED, LAUNCHING, RUNNING,
    /** Prepare-only run finished installing. */
    PREPARED,
    PASSED, FAILED, CRASHED, TIMED_OUT, ERROR, CANCELLED,
    /** Not run: some mod entry has no file for this target ({@code run.onlyTargetsWithAllMods}). */
    SKIPPED;

    public boolean terminal() {
        return ordinal() >= PREPARED.ordinal();
    }

    public boolean success() {
        return this == PASSED || this == PREPARED;
    }

    /** A real failure, as opposed to success or a deliberate skip. */
    public boolean failure() {
        return terminal() && !success() && this != SKIPPED;
    }
}

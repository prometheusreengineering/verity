package dev.verity.core.run;

/** Lifecycle of one client. */
public enum Status {
    PENDING, PREPARING, QUEUED, LAUNCHING, RUNNING,
    /** Prepare-only run finished installing. */
    PREPARED,
    PASSED, FAILED, CRASHED, TIMED_OUT, ERROR, CANCELLED;

    public boolean terminal() {
        return ordinal() >= PREPARED.ordinal();
    }

    public boolean success() {
        return this == PASSED || this == PREPARED;
    }
}

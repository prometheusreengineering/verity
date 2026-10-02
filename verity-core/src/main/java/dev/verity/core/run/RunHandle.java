package dev.verity.core.run;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A run in progress. */
public final class RunHandle {
    private final CompletableFuture<RunReport> report;
    private final List<ClientSession> sessions;

    RunHandle(CompletableFuture<RunReport> report, List<ClientSession> sessions) {
        this.report = report;
        this.sessions = sessions;
    }

    public CompletableFuture<RunReport> report() {
        return report;
    }

    public List<Target> targets() {
        return sessions.stream().map(ClientSession::target).toList();
    }

    public void cancel() {
        sessions.forEach(ClientSession::cancel);
    }

    public void cancel(String targetId) {
        sessions.stream().filter(s -> s.target().id().equals(targetId)).forEach(ClientSession::cancel);
    }
}

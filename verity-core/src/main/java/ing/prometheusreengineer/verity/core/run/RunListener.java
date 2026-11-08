package ing.prometheusreengineer.verity.core.run;

import java.nio.file.Path;
import java.util.List;

/** Live run events for the CLI. Called from worker threads. */
public interface RunListener {
    default void runStarted(String profile, List<Target> targets, Path runDir) {
    }

    default void warning(String message) {
    }

    default void targetStatus(Target target, Status status, String message) {
    }

    default void targetProgress(Target target, String stage, long done, long total) {
    }

    default void targetLog(Target target, String line) {
    }

    default void targetCheck(Target target, CheckResult check) {
    }

    default void runFinished(RunReport report) {
    }

    /** Fans out to several listeners. */
    static RunListener of(List<RunListener> listeners) {
        return new RunListener() {
            @Override
            public void runStarted(String profile, List<Target> targets, Path runDir) {
                listeners.forEach(l -> l.runStarted(profile, targets, runDir));
            }

            @Override
            public void warning(String message) {
                listeners.forEach(l -> l.warning(message));
            }

            @Override
            public void targetStatus(Target target, Status status, String message) {
                listeners.forEach(l -> l.targetStatus(target, status, message));
            }

            @Override
            public void targetProgress(Target target, String stage, long done, long total) {
                listeners.forEach(l -> l.targetProgress(target, stage, done, total));
            }

            @Override
            public void targetLog(Target target, String line) {
                listeners.forEach(l -> l.targetLog(target, line));
            }

            @Override
            public void targetCheck(Target target, CheckResult check) {
                listeners.forEach(l -> l.targetCheck(target, check));
            }

            @Override
            public void runFinished(RunReport report) {
                listeners.forEach(l -> l.runFinished(report));
            }
        };
    }
}

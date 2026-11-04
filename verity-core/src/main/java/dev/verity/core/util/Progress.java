package dev.verity.core.util;

/** Receives coarse progress updates from long-running preparation steps. */
public interface Progress {
    Progress NONE = new Progress() {
        @Override
        public void stage(String description) {
        }

        @Override
        public void progress(long done, long total) {
        }
    };

    void stage(String description);

    void progress(long done, long total);
}

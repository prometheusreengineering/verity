package ing.prometheusreengineer.verity.core.ci;

import ing.prometheusreengineer.verity.core.profile.ProfileException;
import java.util.Locale;

/** An operating system of hosted CI runners, named the way GitHub's {@code runner.os} names it. */
public enum CiOs {
    LINUX("Linux", "ubuntu-latest", 6),
    WINDOWS("Windows", "windows-latest", 5);

    /** {@code runner.os}, also used in cache keys and artifact names. */
    public final String runnerOs;
    public final String defaultRunner;
    /**
     * Clients at once on a 4-core hosted runner. They are CPU-bound, so more barely helps; these were the fastest in
     * the parallel benchmark (docs/performance.md).
     */
    public final int clients;

    CiOs(String runnerOs, String defaultRunner, int clients) {
        this.runnerOs = runnerOs;
        this.defaultRunner = defaultRunner;
        this.clients = clients;
    }

    public static CiOs parse(String s) throws ProfileException {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "linux", "ubuntu", "ubuntu-latest" -> LINUX;
            case "windows", "win", "windows-latest" -> WINDOWS;
            default -> throw new ProfileException("Unknown OS '" + s + "': expected linux or windows");
        };
    }

    /** The OS a report was written on, from its {@code host} ("name (Windows Server 2022 amd64)"), or null. */
    public static CiOs fromHost(String host) {
        if (host == null) return null;
        if (host.contains("Windows")) return WINDOWS;
        if (host.contains("Linux")) return LINUX;
        return null;
    }
}

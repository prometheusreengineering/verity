package ing.prometheusreengineer.verity.core.run;

/** One {@code Verity.pass/fail} report from inside the game. */
public record CheckResult(String name, boolean passed, String message, String detail, long atMillis) {
}

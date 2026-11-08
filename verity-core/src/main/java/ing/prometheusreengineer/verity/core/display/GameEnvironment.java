package ing.prometheusreengineer.verity.core.display;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a game process inherits from Verity's environment: everything except credentials. The game runs the code under
 * test (a pull request's, say), which must never see the Microsoft refresh token or a CI token; it only gets the
 * Minecraft session it plays with. A variable a profile sets explicitly ({@code game.env}) still goes through.
 */
public final class GameEnvironment {
    /** Upper case; Windows variable names are case-insensitive. */
    static final Set<String> SECRETS = Set.of(
            "VERITY_MSA_REFRESH_TOKEN",
            "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_ENTERPRISE_TOKEN",
            "ACTIONS_RUNTIME_TOKEN", "ACTIONS_RUNTIME_URL", "ACTIONS_ID_TOKEN_REQUEST_TOKEN", "ACTIONS_ID_TOKEN_REQUEST_URL",
            "ACTIONS_CACHE_URL", "ACTIONS_RESULTS_URL");

    private GameEnvironment() {}

    public static boolean isSecret(String name) {
        return SECRETS.contains(name.toUpperCase(Locale.ROOT));
    }

    /** The inherited variables without credentials, then the spec's own (display, sandbox, profile). */
    public static Map<String, String> of(Map<String, String> inherited, Map<String, String> spec) {
        Map<String, String> env = new LinkedHashMap<>();
        inherited.forEach((k, v) -> {
            if (!isSecret(k)) env.put(k, v);
        });
        env.putAll(spec);
        return env;
    }
}

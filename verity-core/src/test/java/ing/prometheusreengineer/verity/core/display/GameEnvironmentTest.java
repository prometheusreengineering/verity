package ing.prometheusreengineer.verity.core.display;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameEnvironmentTest {
    @Test
    void theGameNeverInheritsCredentials() {
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("PATH", "/usr/bin");
        inherited.put("VERITY_MSA_REFRESH_TOKEN", "refresh");
        inherited.put("GITHUB_TOKEN", "ghs_x");
        inherited.put("GH_TOKEN", "ghs_y");
        inherited.put("ACTIONS_RUNTIME_TOKEN", "runtime");
        inherited.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "oidc");
        inherited.put("github_token", "lower case, as Windows allows");

        Map<String, String> env = GameEnvironment.of(inherited, Map.of("DISPLAY", ":99"));

        assertThat(env).containsOnlyKeys("PATH", "DISPLAY");
    }

    @Test
    void whatTheProfileSetsExplicitlyStillGoesThrough() {
        Map<String, String> env = GameEnvironment.of(Map.of("GITHUB_TOKEN", "inherited"), Map.of("GITHUB_TOKEN", "from game.env"));

        assertThat(env).containsEntry("GITHUB_TOKEN", "from game.env");
    }

    @Test
    void ordinaryVariablesAreNotSecrets() {
        assertThat(GameEnvironment.isSecret("HOME")).isFalse();
        assertThat(GameEnvironment.isSecret("GITHUB_WORKSPACE")).isFalse();
        assertThat(GameEnvironment.isSecret("VERITY_HOME")).isFalse();
        assertThat(GameEnvironment.isSecret("Verity_Msa_Refresh_Token")).isTrue();
    }
}

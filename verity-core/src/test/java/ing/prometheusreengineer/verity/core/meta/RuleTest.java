package ing.prometheusreengineer.verity.core.meta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import ing.prometheusreengineer.verity.core.util.Json;
import ing.prometheusreengineer.verity.core.util.Os;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleTest {
    static final Rule.Environment LINUX = new Rule.Environment(Os.LINUX, "x86_64", "6.0", Map.of());
    static final Rule.Environment WIN = new Rule.Environment(Os.WINDOWS, "x86_64", "10.0", Map.of());
    static final Rule.Environment OSX = new Rule.Environment(Os.OSX, "arm64", "14.0", Map.of());

    @Test
    void lwjgl2Style() throws Exception {
        List<Rule> rules = Json.MAPPER.readValue("""
                [{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]""", new TypeReference<>() {});
        assertThat(Rule.allowed(rules, LINUX)).isTrue();
        assertThat(Rule.allowed(rules, WIN)).isTrue();
        assertThat(Rule.allowed(rules, OSX)).isFalse();
    }

    @Test
    void onlyOs() throws Exception {
        List<Rule> rules = Json.MAPPER.readValue("""
                [{"action":"allow","os":{"name":"windows"}}]""", new TypeReference<>() {});
        assertThat(Rule.allowed(rules, WIN)).isTrue();
        assertThat(Rule.allowed(rules, LINUX)).isFalse();
        assertThat(Rule.allowed(null, LINUX)).isTrue();
    }

    @Test
    void featuresAndArch() throws Exception {
        List<Rule> demo = Json.MAPPER.readValue("""
                [{"action":"allow","features":{"is_demo_user":true}}]""", new TypeReference<>() {});
        assertThat(Rule.allowed(demo, LINUX)).isFalse();
        assertThat(Rule.allowed(demo, LINUX.withFeatures(Map.of("is_demo_user", true)))).isTrue();
        List<Rule> x86 = Json.MAPPER.readValue("""
                [{"action":"allow","os":{"arch":"x86"}}]""", new TypeReference<>() {});
        assertThat(Rule.allowed(x86, LINUX)).isFalse();
    }

    @Test
    void argumentsDeserialize() throws Exception {
        VersionJson.Arguments a = Json.MAPPER.readValue("""
                {"game":["--username","${auth_player_name}",
                  {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}"]}],
                 "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":"-XstartOnFirstThread"}]}
                """, VersionJson.Arguments.class);
        assertThat(a.game).hasSize(3);
        assertThat(a.game.get(2).values()).containsExactly("--width", "${resolution_width}");
        assertThat(a.jvm.get(0).values()).containsExactly("-XstartOnFirstThread");
        assertThat(a.jvm.get(0).rules()).hasSize(1);
    }
}

package ing.prometheusreengineer.verity.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionPredicateTest {
    static List<String> V = List.of("1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.19.4", "1.20", "1.20.1", "1.20.4", "1.20.6", "1.21", "1.21.1", "1.21.10", "26.1");

    static List<String> matching(VersionPredicate p) {
        return V.stream().filter(p).toList();
    }

    @Test
    void comparatorsAndWildcards() {
        assertThat(matching(VersionPredicate.parse(">=1.20 <1.21"))).containsExactly("1.20", "1.20.1", "1.20.4", "1.20.6");
        assertThat(matching(VersionPredicate.parse("1.20.x"))).containsExactly("1.20", "1.20.1", "1.20.4", "1.20.6");
        assertThat(matching(VersionPredicate.parse("1.20.*"))).containsExactly("1.20", "1.20.1", "1.20.4", "1.20.6");
        assertThat(matching(VersionPredicate.parse("<=1.12.2"))).containsExactly("1.7.10", "1.8.9", "1.12.2");
        assertThat(matching(VersionPredicate.parse("1.20.1"))).containsExactly("1.20.1");
        assertThat(matching(VersionPredicate.parse(">1.21.1"))).containsExactly("1.21.10", "26.1");
        assertThat(VersionPredicate.parse("*").isAny()).isTrue();
        assertThat(VersionPredicate.parse("").isAny()).isTrue();
    }

    @Test
    void fabricStyle() {
        assertThat(matching(VersionPredicate.parse("~1.20.1"))).containsExactly("1.20.1", "1.20.4", "1.20.6");
        assertThat(matching(VersionPredicate.parse("^1.20"))).containsExactly("1.20", "1.20.1", "1.20.4", "1.20.6", "1.21", "1.21.1", "1.21.10");
        assertThat(matching(VersionPredicate.parse(">=1.16-alpha.20.10.a"))).startsWith("1.16.5").contains("26.1");
        assertThat(matching(VersionPredicate.anyOf(List.of("1.8.9", "1.12.2")))).containsExactly("1.8.9", "1.12.2");
    }

    @Test
    void mavenRanges() {
        assertThat(matching(VersionPredicate.parse("[1.8.9,1.12.2]"))).containsExactly("1.8.9", "1.12.2");
        assertThat(matching(VersionPredicate.parse("[1.21,)"))).containsExactly("1.21", "1.21.1", "1.21.10", "26.1");
        assertThat(matching(VersionPredicate.parse("(,1.13)"))).containsExactly("1.7.10", "1.8.9", "1.12.2");
        assertThat(matching(VersionPredicate.parse("[1.20.1,1.20.2)"))).containsExactly("1.20.1");
        assertThat(matching(VersionPredicate.parse("[1.8.9],[1.20.1,1.20.4]"))).containsExactly("1.8.9", "1.20.1", "1.20.4");
        assertThat(VersionPredicate.parseMaven("1.20.1").isAny()).isTrue();
        assertThat(matching(VersionPredicate.parseMaven("[1.20.1]"))).containsExactly("1.20.1");
    }

    @Test
    void alternatives() {
        assertThat(matching(VersionPredicate.parse("1.8.9 || >=1.21.1"))).containsExactly("1.8.9", "1.21.1", "1.21.10", "26.1");
        assertThat(matching(VersionPredicate.parse("1.8.9, 1.12.2"))).containsExactly("1.8.9", "1.12.2");
    }
}

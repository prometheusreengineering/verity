package dev.verity.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HookServerTest {
    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsUtf8LinesAndDropsOversizedOnesWithoutBufferingThem() throws Exception {
        InputStream in = in("héllo\r\n" + "x".repeat(100) + "\nnext\nlast");
        assertThat(HookServer.readLine(in, 10)).isEqualTo("héllo");
        assertThat(HookServer.readLine(in, 10)).isSameAs(HookServer.TOO_LONG);
        assertThat(HookServer.readLine(in, 10)).isEqualTo("next");
        assertThat(HookServer.readLine(in, 10)).isEqualTo("last");
        assertThat(HookServer.readLine(in, 10)).isNull();
    }

    @Test
    void anEmptyLineIsNotEndOfStream() throws Exception {
        InputStream in = in("\nafter\n");
        assertThat(HookServer.readLine(in, 10)).isEmpty();
        assertThat(HookServer.readLine(in, 10)).isEqualTo("after");
        assertThat(HookServer.readLine(in, 10)).isNull();
    }
}

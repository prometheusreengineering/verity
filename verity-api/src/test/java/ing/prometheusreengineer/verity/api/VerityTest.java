package ing.prometheusreengineer.verity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VerityTest {
    @AfterEach
    void cleanup() {
        System.clearProperty(Verity.PROP_PORT);
        System.clearProperty(Verity.PROP_TOKEN);
        System.clearProperty(Verity.PROP_TARGET);
        Verity.reset();
    }

    @Test
    void inactiveWithoutProperties() {
        assertThat(Verity.isActive()).isFalse();
        Verity.pass("x"); // must not throw
        Verity.finish();
    }

    @Test
    void sendsEventsAndWaitsForFinishAck() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            System.setProperty(Verity.PROP_PORT, String.valueOf(server.getLocalPort()));
            System.setProperty(Verity.PROP_TOKEN, "secret");
            System.setProperty(Verity.PROP_TARGET, "1.20.1-fabric");

            CompletableFuture<List<String>> lines = CompletableFuture.supplyAsync(() -> {
                List<String> got = new ArrayList<>();
                try (Socket s = server.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream o = s.getOutputStream();
                    String line;
                    while ((line = r.readLine()) != null) {
                        got.add(line);
                        if (line.contains("\"finish\"")) {
                            o.write("ack\n".getBytes(StandardCharsets.UTF_8));
                            o.flush();
                            break;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return got;
            });

            assertThat(Verity.target()).isEqualTo("1.20.1-fabric");
            Verity.pass("loaded");
            Verity.fail("quote\"d", "line1\nline2");
            assertThat(Verity.test("boom", () -> { throw new IllegalStateException("nope"); })).isFalse();
            Verity.attach("a.bin", new byte[] {1, 2, 3, 4});
            Verity.finish();

            List<String> got = lines.get(10, TimeUnit.SECONDS);
            assertThat(got.get(0)).contains("\"type\":\"hello\"").contains("\"token\":\"secret\"");
            assertThat(got.get(1)).isEqualTo("{\"type\":\"pass\",\"name\":\"loaded\"}");
            assertThat(got.get(2)).isEqualTo("{\"type\":\"fail\",\"name\":\"quote\\\"d\",\"message\":\"line1\\nline2\"}");
            assertThat(got.get(3)).contains("\"name\":\"boom\"").contains("IllegalStateException");
            assertThat(got.get(4)).isEqualTo("{\"type\":\"attach\",\"name\":\"a.bin\",\"data\":\"AQIDBA==\"}");
            assertThat(got.get(5)).isEqualTo("{\"type\":\"finish\"}");
        }
    }

    @Test
    void base64MatchesJdk() {
        for (int len = 0; len < 20; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) data[i] = (byte) (i * 37 - 5);
            assertThat(Verity.Base64.encode(data)).isEqualTo(java.util.Base64.getEncoder().encodeToString(data));
        }
    }
}

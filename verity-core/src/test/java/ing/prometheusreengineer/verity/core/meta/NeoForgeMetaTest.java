package ing.prometheusreengineer.verity.core.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ing.prometheusreengineer.verity.core.net.Http;
import ing.prometheusreengineer.verity.core.net.MetaCache;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoForgeMetaTest {
    @TempDir
    Path tmp;

    HttpServer server;
    final Map<String, String> routes = new HashMap<>();
    NeoForgeMeta meta;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = routes.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        Http http = new Http(HttpClient.newBuilder().proxy(ProxySelector.of(null)).build());
        meta = new NeoForgeMeta(new MetaCache(http, tmp, Duration.ZERO), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void usesVersionApiWhenMetadataXmlIsMissing() throws Exception {
        // unordered on purpose: ordering must not depend on the server
        routes.put("/api/maven/versions/releases/net/neoforged/neoforge",
                "{\"isSnapshot\":false,\"versions\":[\"21.1.9\",\"21.1.77\",\"21.1.200-beta\",\"20.4.237\",\"21.0.167\"]}");
        routes.put("/api/maven/versions/releases/net/neoforged/forge", "{\"versions\":[\"1.20.1-47.1.3\",\"1.20.1-47.1.106\"]}");

        assertThat(meta.supportedGameVersions()).contains("1.21.1", "1.20.4", "1.21", "1.20.1");
        assertThat(meta.loaderVersions("1.21.1")).containsExactly("21.1.200-beta", "21.1.77", "21.1.9");
        assertThat(meta.resolve("1.21.1", "recommended")).isEqualTo("21.1.77");
        assertThat(meta.resolve("1.21.1", "latest")).isEqualTo("21.1.200-beta");
        assertThat(meta.resolve("1.20.1", null)).isEqualTo("1.20.1-47.1.106");
    }

    @Test
    void fallsBackToMetadataXml() throws Exception {
        routes.put("/releases/net/neoforged/neoforge/maven-metadata.xml",
                "<metadata><versioning><versions><version>21.1.1</version><version>21.1.2</version></versions></versioning></metadata>");
        assertThat(meta.resolve("1.21.1", "recommended")).isEqualTo("21.1.2");
    }

    @Test
    void reportsBothSourcesWhenNothingWorks() {
        assertThatThrownBy(() -> meta.loaderVersions("1.21.1")).isInstanceOf(IOException.class)
                .hasMessageContaining("api/maven/versions").hasMessageContaining("maven-metadata.xml");
    }
}

package ing.prometheusreengineer.verity.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ing.prometheusreengineer.verity.core.util.Hashing;
import ing.prometheusreengineer.verity.core.util.Progress;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloaderTest {
    HttpServer server;
    String base;
    AtomicInteger hits = new AtomicInteger();
    volatile boolean online = true;
    AtomicInteger limited = new AtomicInteger();
    List<String> storageAuth = new java.util.concurrent.CopyOnWriteArrayList<>();

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/storage")) storageAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (!online || path.startsWith("/missing")) {
                ex.sendResponseHeaders(404, -1);
            } else if (path.startsWith("/limited") && limited.getAndDecrement() > 0) {
                ex.sendResponseHeaders(429, -1);
            } else if (path.startsWith("/forbidden")) {
                ex.sendResponseHeaders(403, -1);
            } else if (path.startsWith("/api/asset")) {
                ex.getResponseHeaders().add("Location", "/storage/mesa.7z?signature=x");
                ex.sendResponseHeaders(302, -1);
            } else {
                byte[] body = ("content of " + path).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    Downloader downloader() {
        return new Downloader(new Http(java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()), 4);
    }

    @Test
    void downloadsVerifiesAndSkipsExisting() throws Exception {
        byte[] expected = "content of /a.jar".getBytes(StandardCharsets.UTF_8);
        Path target = tmp.resolve("libs/a.jar");
        Downloader.Artifact a = Downloader.Artifact.of(base + "/a.jar", target, Hashing.sha1(expected), expected.length);
        Downloader d = downloader();
        d.download(a);
        assertThat(Files.readAllBytes(target)).isEqualTo(expected);
        int before = hits.get();
        d.download(a);
        assertThat(hits.get()).isEqualTo(before);
    }

    @Test
    void rejectsWrongHashAndFallsBackToMirror() throws Exception {
        Downloader d = downloader();
        Path bad = tmp.resolve("bad.jar");
        assertThatThrownBy(() -> d.download(Downloader.Artifact.of(base + "/x", bad, "0000", -1))).isInstanceOf(IOException.class);
        assertThat(bad).doesNotExist();

        Path mirrored = tmp.resolve("m.jar");
        d.download(new Downloader.Artifact(List.of(URI.create(base + "/missing/m.jar"), URI.create(base + "/m.jar")), mirrored, null, -1, true));
        assertThat(Files.readString(mirrored)).isEqualTo("content of /m.jar");
    }

    @Test
    void retriesRateLimitsAndNamesTheStatusWhenItGivesUp() throws Exception {
        limited.set(1);
        Path target = tmp.resolve("limited.jar");
        downloader().download(Downloader.Artifact.of(base + "/limited.jar", target, null, -1));
        assertThat(target).hasContent("content of /limited.jar");

        assertThatThrownBy(() -> downloader().download(Downloader.Artifact.of(base + "/forbidden.jar", tmp.resolve("f.jar"), null, -1)))
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void redirectTargetSendsTheTokenOnlyToTheFirstHost() throws Exception {
        Http http = new Http(java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build());
        URI signed = http.redirectTarget(URI.create(base + "/api/asset/1"), java.util.Map.of("Authorization", "Bearer secret"));
        assertThat(signed.toString()).isEqualTo(base + "/storage/mesa.7z?signature=x");
        assertThat(storageAuth).isEmpty();

        Path target = tmp.resolve("mesa.7z");
        new Downloader(http, 1).download(new Downloader.Artifact(List.of(signed), target, null, -1, true));
        assertThat(target).hasContent("content of /storage/mesa.7z");
        assertThat(storageAuth).containsExactly("null");

        assertThatThrownBy(() -> http.redirectTarget(URI.create(base + "/plain"), java.util.Map.of()))
                .hasMessageContaining("HTTP 200");
    }

    @Test
    void downloadAllReportsProgress() throws Exception {
        List<Downloader.Artifact> list = new ArrayList<>();
        for (int i = 0; i < 25; i++) list.add(Downloader.Artifact.of(base + "/f" + i, tmp.resolve("f" + i), null, -1));
        list.add(list.get(0)); // duplicate is coalesced
        AtomicInteger last = new AtomicInteger();
        downloader().downloadAll(list, new Progress() {
            public void stage(String s) {
            }

            public void progress(long done, long total) {
                assertThat(total).isEqualTo(25);
                last.accumulateAndGet((int) done, Math::max);
            }
        });
        assertThat(last.get()).isEqualTo(25);
        assertThat(tmp.resolve("f24")).hasContent("content of /f24");
    }

    @Test
    void metaCacheServesStaleWhenOffline() throws Exception {
        Http http = new Http(java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build());
        MetaCache cache = new MetaCache(http, tmp.resolve("cache"), Duration.ZERO);
        assertThat(cache.get(base + "/list.json")).isEqualTo("content of /list.json");
        online = false;
        assertThat(cache.get(base + "/list.json")).isEqualTo("content of /list.json");
        assertThatThrownBy(() -> cache.get(base + "/other.json")).isInstanceOf(IOException.class);
    }
}

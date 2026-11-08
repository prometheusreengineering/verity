package ing.prometheusreengineer.verity.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Thin wrapper around {@link HttpClient} with Verity's defaults (proxy, redirects, timeouts, user agent). */
public final class Http {
    public static final String USER_AGENT = "Verity/0.1 (+https://github.com/prometheusreengineering/verity)";

    private final HttpClient client;

    public Http() {
        this(HttpClient.newBuilder()
                .proxy(ProxyConfig.selector())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build());
    }

    public Http(HttpClient client) {
        this.client = client;
    }

    public HttpClient client() {
        return client;
    }

    public HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent", USER_AGENT);
    }

    public String getString(URI uri) throws IOException {
        return new String(getBytes(uri), StandardCharsets.UTF_8);
    }

    public byte[] getBytes(URI uri) throws IOException {
        HttpResponse<byte[]> r = send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        check(uri, r.statusCode());
        return r.body();
    }

    public InputStream getStream(URI uri) throws IOException {
        HttpResponse<InputStream> r = send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() / 100 != 2) {
            r.body().close();
            check(uri, r.statusCode());
        }
        return r.body();
    }

    /** POSTs a form or JSON body and returns status + body; non-2xx is returned, not thrown. */
    public HttpResponse<String> post(URI uri, String contentType, String body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = request(uri).header("Content-Type", contentType).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(URI uri, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = request(uri).GET();
        headers.forEach(b::header);
        return send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Where {@code uri} redirects to, without following it, so {@code headers} (such as a token) reach only
     * {@code uri} and never the redirect's host. Throws unless the answer is a redirect.
     */
    public URI redirectTarget(URI uri, Map<String, String> headers) throws IOException {
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(client.connectTimeout().orElse(Duration.ofSeconds(20)));
        client.proxy().ifPresent(b::proxy);
        HttpRequest.Builder req = request(uri).GET();
        headers.forEach(req::header);
        HttpResponse<Void> r = send(b.build(), req.build(), HttpResponse.BodyHandlers.discarding());
        String location = r.headers().firstValue("Location").orElse(null);
        if (r.statusCode() / 100 != 3 || location == null) throw new HttpStatusException(uri, r.statusCode());
        return uri.resolve(location);
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        return send(client, req, handler);
    }

    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + req.uri(), e);
        }
    }

    private static void check(URI uri, int status) throws IOException {
        if (status / 100 != 2) throw new HttpStatusException(uri, status);
    }

    public static final class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(URI uri, int status) {
            super("HTTP " + status + " for " + uri);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}

package dev.verity.core.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the JVM's configured proxy ({@code https.proxyHost}...), or else the conventional
 * {@code HTTPS_PROXY}/{@code HTTP_PROXY}/{@code NO_PROXY} environment variables CI systems set.
 */
public final class ProxyConfig {
    private ProxyConfig() {
    }

    public static ProxySelector selector() {
        if (System.getProperty("https.proxyHost") != null || System.getProperty("http.proxyHost") != null
                || System.getProperty("socksProxyHost") != null || Boolean.getBoolean("java.net.useSystemProxies")) {
            return ProxySelector.getDefault();
        }
        String https = env("HTTPS_PROXY");
        String http = env("HTTP_PROXY");
        if (https == null && http == null) return ProxySelector.getDefault();
        List<String> noProxy = new ArrayList<>();
        String np = env("NO_PROXY");
        if (np != null) for (String s : np.split(",")) if (!s.isBlank()) noProxy.add(s.trim().toLowerCase(Locale.ROOT));
        return new EnvSelector(parse(https != null ? https : http), parse(http != null ? http : https), noProxy);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null) v = System.getenv(name.toLowerCase(Locale.ROOT));
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static InetSocketAddress parse(String url) {
        URI u = URI.create(url.contains("://") ? url : "http://" + url);
        int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
        return InetSocketAddress.createUnresolved(u.getHost(), port);
    }

    private static final class EnvSelector extends ProxySelector {
        private final InetSocketAddress https;
        private final InetSocketAddress http;
        private final List<String> noProxy;

        EnvSelector(InetSocketAddress https, InetSocketAddress http, List<String> noProxy) {
            this.https = https;
            this.http = http;
            this.noProxy = noProxy;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.startsWith("127.")) return List.of(Proxy.NO_PROXY);
            for (String n : noProxy) {
                String suffix = n.startsWith("*") ? n.substring(1) : n;
                if (n.equals("*") || host.equals(suffix.replaceFirst("^\\.", "")) || host.endsWith(suffix.startsWith(".") ? suffix : "." + suffix)) {
                    return List.of(Proxy.NO_PROXY);
                }
            }
            return List.of(new Proxy(Proxy.Type.HTTP, "https".equals(uri.getScheme()) ? https : http));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }
}

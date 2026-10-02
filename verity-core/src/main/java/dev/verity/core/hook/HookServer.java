package dev.verity.core.hook;

import com.fasterxml.jackson.databind.JsonNode;
import dev.verity.core.util.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Localhost server receiving JSON-line events from verity-api inside game clients. Each client gets a random
 * token so a connection is attributed to the right target and nothing else on the machine can inject results.
 */
public final class HookServer implements AutoCloseable {
    private static final int MAX_LINE = 16 * 1024 * 1024;

    private final ServerSocket server;
    private final Map<String, HookListener> listeners = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    public HookServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("verity-hook-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Registers a client and returns the token to pass as {@code -Dverity.token}. */
    public String register(HookListener listener) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        listeners.put(token, listener);
        return token;
    }

    public void unregister(String token) {
        listeners.remove(token);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                Thread.ofVirtual().name("verity-hook-conn").start(() -> handle(s));
            } catch (IOException e) {
                if (!closed) System.err.println("[verity] hook accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(0);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 64 * 1024);
            OutputStream out = socket.getOutputStream();
            String first = in.readLine();
            if (first == null) return;
            JsonNode hello = Json.MAPPER.readTree(first);
            if (!"hello".equals(hello.path("type").asText())) return;
            HookListener l = listeners.get(hello.path("token").asText(""));
            if (l == null) return;
            l.onConnected(hello.path("java").asText(null));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > MAX_LINE) continue;
                JsonNode n;
                try {
                    n = Json.MAPPER.readTree(line);
                } catch (IOException e) {
                    l.onLog("[verity] malformed hook message: " + line.substring(0, Math.min(200, line.length())));
                    continue;
                }
                switch (n.path("type").asText()) {
                    case "pass" -> l.onPass(n.path("name").asText("check"));
                    case "fail" -> l.onFail(n.path("name").asText("check"), n.path("message").asText(null),
                            n.path("detail").asText(null));
                    case "log" -> l.onLog(n.path("message").asText(""));
                    case "attach" -> {
                        try {
                            l.onAttach(n.path("name").asText("attachment"), Base64.getDecoder().decode(n.path("data").asText("")));
                        } catch (IllegalArgumentException e) {
                            l.onLog("[verity] bad attachment encoding");
                        }
                    }
                    case "finish" -> {
                        l.onFinish();
                        out.write("ack\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException ignored) {
            // client went away; the session notices through process exit
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }
}

package ing.prometheusreengineer.verity.core.hook;

import com.fasterxml.jackson.databind.JsonNode;
import ing.prometheusreengineer.verity.core.util.Json;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /** Attachments arrive base64-encoded on one line, so events can be large; the hello line can't. */
    static final int MAX_LINE = 16 * 1024 * 1024;
    static final int MAX_HELLO = 64 * 1024;
    /** Returned by {@link #readLine} for a line over the limit; compared by identity. */
    static final String TOO_LONG = new String("<too long>");

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
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = socket.getOutputStream();
            String first = readLine(in, MAX_HELLO);
            if (first == null || first == TOO_LONG) return;
            JsonNode hello = Json.MAPPER.readTree(first);
            if (!"hello".equals(hello.path("type").asText())) return;
            HookListener l = listeners.get(hello.path("token").asText(""));
            if (l == null) return;
            l.onConnected(hello.path("java").asText(null));
            String line;
            while ((line = readLine(in, MAX_LINE)) != null) {
                if (line == TOO_LONG) {
                    l.onLog("[verity] dropped a hook message over " + MAX_LINE + " bytes");
                    continue;
                }
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

    /**
     * Reads one UTF-8 line without buffering more than {@code max} bytes of it: the rest of an oversized line is
     * skipped and {@link #TOO_LONG} returned. Returns null at end of stream.
     */
    static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean tooLong = false;
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (tooLong) continue;
            if (buf.size() >= max) {
                tooLong = true;
                buf.reset();
                continue;
            }
            buf.write(b);
        }
        if (tooLong) return TOO_LONG;
        if (b == -1 && buf.size() == 0) return null;
        String s = buf.toString(StandardCharsets.UTF_8);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }
}

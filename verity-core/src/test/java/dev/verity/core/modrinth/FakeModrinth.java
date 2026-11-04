package dev.verity.core.modrinth;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.verity.core.TestJars;
import dev.verity.core.util.Hashing;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A local stand-in for api.modrinth.com and its CDN, serving Essential-shaped data. */
public final class FakeModrinth implements AutoCloseable {
    public final HttpServer server;
    public final String base;
    private final Map<String, byte[]> routes = new ConcurrentHashMap<>();
    private final Path dir;

    public FakeModrinth(Path dir) throws IOException {
        this.dir = dir;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = routes.get(path);
            if (body == null && path.equals("/v2/search")) body = routes.get("/v2/search");
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/";
    }

    public void route(String path, String json) {
        routes.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    public ObjectNode project(String id, String slug, String title) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("id", id);
        p.put("slug", slug);
        p.put("title", title);
        route("/v2/project/" + id, p.toString());
        route("/v2/project/" + slug, p.toString());
        return p;
    }

    /** Adds a version with a real jar file served from /files/; returns its node so tests can add dependencies. */
    public ObjectNode version(ArrayNode list, String projectId, String id, String number, String type, String date,
                              List<String> loaders, List<String> games, String fileName) throws IOException {
        Path jar = TestJars.jar(dir.resolve(fileName), Map.of("marker.txt", fileName));
        byte[] bytes = Files.readAllBytes(jar);
        routes.put("/files/" + fileName, bytes);
        ObjectNode v = list.addObject();
        v.put("id", id);
        v.put("project_id", projectId);
        v.put("version_number", number);
        v.put("version_type", type);
        v.put("date_published", date);
        ArrayNode l = v.putArray("loaders");
        loaders.forEach(l::add);
        ArrayNode g = v.putArray("game_versions");
        games.forEach(g::add);
        ObjectNode f = v.putArray("files").addObject();
        f.put("url", base.replace("/v2/", "/files/") + fileName);
        f.put("filename", fileName);
        f.put("primary", true);
        f.put("size", bytes.length);
        f.putObject("hashes").put("sha1", Hashing.sha1(bytes));
        v.putArray("dependencies");
        route("/v2/version/" + id, v.toString());
        return v;
    }

    public void versions(String projectId, ArrayNode list) {
        route("/v2/project/" + projectId + "/version", list.toString());
    }

    public static void require(ObjectNode version, String projectId) {
        ObjectNode d = ((ArrayNode) version.get("dependencies")).addObject();
        d.put("project_id", projectId);
        d.put("dependency_type", "required");
    }

    /** Essential-like project (id ESS) with Fabric API (id FAPI) as a dependency of its Fabric builds. */
    public static FakeModrinth essential(Path dir) throws IOException {
        FakeModrinth m = new FakeModrinth(dir);
        m.project("ESS", "essential", "Essential");
        m.project("FAPI", "fabric-api", "Fabric API");
        ArrayNode ess = Json.MAPPER.createArrayNode();
        ObjectNode fab = m.version(ess, "ESS", "v-fab-1201", "1.3.0+fabric-1.20.1", "release", "2026-01-01T00:00:00Z",
                List.of("fabric"), List.of("1.20.1"), "essential-fabric-1.20.1.jar");
        require(fab, "FAPI");
        m.version(ess, "ESS", "v-fab-1201-beta", "1.3.1-beta+fabric-1.20.1", "beta", "2026-02-01T00:00:00Z",
                List.of("fabric"), List.of("1.20.1"), "essential-fabric-1.20.1-beta.jar");
        m.version(ess, "ESS", "v-forge-189", "1.3.0+forge-1.8.9", "release", "2026-01-02T00:00:00Z",
                List.of("forge"), List.of("1.8.9"), "essential-forge-1.8.9.jar");
        m.version(ess, "ESS", "v-forge-1201", "1.3.0+forge-1.20.1", "release", "2026-01-03T00:00:00Z",
                List.of("forge"), List.of("1.20.1"), "essential-forge-1.20.1.jar");
        m.version(ess, "ESS", "v-neo-1211", "1.3.0+neoforge-1.21.1", "release", "2026-01-04T00:00:00Z",
                List.of("neoforge"), List.of("1.21.1"), "essential-neoforge-1.21.1.jar");
        m.version(ess, "ESS", "v-fab-1211-alpha", "1.4.0-alpha+fabric-1.21.1", "alpha", "2026-03-01T00:00:00Z",
                List.of("fabric"), List.of("1.21.1", "24w14a"), "essential-fabric-1.21.1-alpha.jar");
        m.version(ess, "ESS", "v-fake", "1.3.0+fabric-fake", "release", "2026-01-05T00:00:00Z",
                List.of("fabric"), List.of("fake-idle"), "essential-fabric-fake.jar");
        m.versions("ESS", ess);
        ArrayNode fapi = Json.MAPPER.createArrayNode();
        m.version(fapi, "FAPI", "fapi-1201", "0.92.0+1.20.1", "release", "2026-01-01T00:00:00Z",
                List.of("fabric"), List.of("1.20.1"), "fabric-api-1.20.1.jar");
        m.versions("FAPI", fapi);
        m.route("/v2/search", "{\"hits\":[{\"project_id\":\"ESS\",\"slug\":\"essential\",\"title\":\"Essential\",\"downloads\":123}]}");
        return m;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

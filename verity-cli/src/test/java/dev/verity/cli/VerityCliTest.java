package dev.verity.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class VerityCliTest {
    @TempDir
    Path tmp;

    record Result(int code, String out, String err) {
    }

    Result run(String... args) {
        CommandLine cl = VerityCli.commandLine();
        cl.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        String[] full = new String[args.length + 1];
        full[0] = args[0];
        full[1] = "--home=" + tmp.resolve("home");
        System.arraycopy(args, 1, full, 2, args.length - 1);
        int code = cl.execute(full);
        return new Result(code, out.toString(), err.toString());
    }

    @Test
    void profileLifecycleAndPlan() throws Exception {
        Path mod = tmp.resolve("mod.jar");
        try (JarOutputStream j = new JarOutputStream(Files.newOutputStream(mod))) {
            j.putNextEntry(new ZipEntry("fabric.mod.json"));
            j.write("{\"id\":\"m\",\"depends\":{\"minecraft\":\"1.20.1\"}}".getBytes());
        }
        Result created = run("profile", "new", "demo", "-t", "1.20.1:fabric", "-t", "1.8.9:forge", "-m", mod.toString());
        assertThat(created.code()).as(created.err()).isZero();
        assertThat(run("profile", "list").out()).contains("demo");
        assertThat(run("profile", "show", "demo").out()).contains("\"loader\" : \"fabric\"");
        assertThat(run("profile", "new", "demo").code()).isEqualTo(VerityCli.CONFIG_ERROR);

        Result plan = run("plan", "demo", "-v");
        assertThat(plan.code()).as(plan.err()).isZero();
        assertThat(plan.out()).contains("1.20.1-fabric").contains("+ mod.jar").contains("1.8.9-forge").contains("- mod.jar");

        Result shard = run("plan", "demo", "--shard", "1/2");
        assertThat(shard.code()).as(shard.err()).isZero();
        assertThat(shard.out()).contains("1 target(s)").contains("1.20.1-fabric").doesNotContain("1.8.9-forge");
        Result badPlan = run("plan", "demo", "--shard", "3/2");
        assertThat(badPlan.code()).isEqualTo(VerityCli.CONFIG_ERROR);
        assertThat(badPlan.err()).contains("Invalid shard");
        Result badRun = run("run", "demo", "--shard", "0/2");
        assertThat(badRun.code()).isEqualTo(VerityCli.CONFIG_ERROR);
        assertThat(badRun.err()).contains("Invalid shard");

        Result matrix = run("matrix", "demo", "--os", "linux,windows", "--max-runners", "4", "--week", "2026-W45",
                "--profile-hash", "h");
        assertThat(matrix.code()).as(matrix.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode m = dev.verity.core.util.Json.tree(matrix.out());
        for (String os : java.util.List.of("Linux", "Windows")) {
            java.util.List<String> ran = new java.util.ArrayList<>();
            m.path("shards").path("include").forEach(j -> {
                if (j.path("os").asText().equals(os)) ran.addAll(java.util.List.of(j.path("targets").asText().split(",")));
            });
            assertThat(ran).containsExactlyInAnyOrder("1.20.1-fabric", "1.8.9-forge");
        }
        assertThat(m.path("hasPrepare").asBoolean()).isTrue();
        // Once every cache exists there's nothing to prepare.
        StringBuilder caches = new StringBuilder();
        m.path("prepare").path("include").forEach(j -> caches
                .append("{\"key\":\"verity-shared-").append(j.path("key").asText()).append("-2026-W45\",\"size_in_bytes\":1}\n")
                .append("{\"key\":\"verity-").append(j.path("os").asText()).append('-').append(j.path("key").asText())
                .append("-2026-W45\",\"size_in_bytes\":1}\n"));
        Path cacheList = tmp.resolve("caches.jsonl");
        Files.writeString(cacheList, caches);
        Result warm = run("matrix", "demo", "--os", "linux,windows", "--max-runners", "4", "--week", "2026-W45",
                "--profile-hash", "h", "--caches", cacheList.toString());
        assertThat(warm.code()).as(warm.err()).isZero();
        assertThat(dev.verity.core.util.Json.tree(warm.out()).path("hasPrepare").asBoolean()).isFalse();
        assertThat(run("matrix", "demo", "--os", "mac").code()).isEqualTo(VerityCli.CONFIG_ERROR);

        Path exported = tmp.resolve("exported.json");
        assertThat(run("profile", "export", "demo", exported.toString()).code()).isZero();
        assertThat(exported).exists();
        assertThat(run("profile", "rm", "demo").code()).isZero();
        assertThat(run("profile", "import", exported.toString()).code()).isZero();
        assertThat(run("cache", "info").out()).contains("libraries");
    }

    @Test
    void configErrorsExitWith2() {
        Result r = run("run", "does-not-exist");
        assertThat(r.code()).isEqualTo(VerityCli.CONFIG_ERROR);
        assertThat(r.err()).contains("No profile named");
        assertThat(run("profile", "new", "x", "-t", "1.20.1:quilt").code()).isEqualTo(VerityCli.CONFIG_ERROR);
    }

    @Test
    void modrinthProfileAndPlan() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.Map<String, String> routes = new java.util.HashMap<>();
        routes.put("/v2/project/essential", "{\"id\":\"ESS\",\"slug\":\"essential\",\"title\":\"Essential\"}");
        routes.put("/v2/project/ESS", routes.get("/v2/project/essential"));
        routes.put("/v2/project/ESS/version", "[{\"id\":\"v1\",\"project_id\":\"ESS\",\"version_number\":\"1.3.0+fabric-1.20.1\","
                + "\"version_type\":\"release\",\"date_published\":\"2026-01-01T00:00:00Z\",\"loaders\":[\"fabric\"],"
                + "\"game_versions\":[\"1.20.1\"],\"files\":[{\"url\":\"http://127.0.0.1/x.jar\",\"filename\":\"essential-fabric.jar\","
                + "\"primary\":true,\"size\":1,\"hashes\":{\"sha1\":\"00\"}}],\"dependencies\":[]}]");
        server.createContext("/", ex -> {
            String body = routes.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        System.setProperty("verity.modrinth.api", "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/");
        try {
            Result created = run("profile", "new", "ess", "-t", "1.20.1:fabric", "-t", "1.12.2:forge", "--modrinth", "essential",
                    "--only-with-all-mods", "--smoke", "180");
            assertThat(created.code()).as(created.err()).isZero();
            String json = run("profile", "show", "ess").out();
            assertThat(json).contains("\"modrinth\" : \"essential\"").contains("\"onlyTargetsWithAllMods\" : true")
                    .contains("\"smokeSec\" : 180");

            Result plan = run("plan", "ess");
            assertThat(plan.code()).as(plan.err()).isZero();
            assertThat(plan.out()).contains("1 skipped").contains("+ essential-fabric.jar")
                    .contains("1.12.2-forge").contains("SKIPPED: no file for modrinth:essential");

            Result versions = run("modrinth", "versions", "essential");
            assertThat(versions.out()).contains("fabric (1): 1.20.1");

            Result sel = run("profile", "new", "sel", "--select-modrinth", "essential", "--modrinth", "essential@1.3.0+fabric-1.20.1");
            assertThat(sel.code()).as(sel.err()).isZero();
            assertThat(run("profile", "show", "sel").out()).contains("\"version\" : \"1.3.0+fabric-1.20.1\"")
                    .contains("\"modrinth\" : [ \"essential\" ]");
        } finally {
            System.clearProperty("verity.modrinth.api");
            server.stop(0);
        }
    }
}

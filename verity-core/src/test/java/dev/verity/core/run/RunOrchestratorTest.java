package dev.verity.core.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.verity.api.Verity;
import dev.verity.core.TestJars;
import dev.verity.core.display.DisplayProvider;
import dev.verity.core.display.DisplaySession;
import dev.verity.core.home.GlobalSettings;
import dev.verity.core.home.VerityHome;
import dev.verity.core.install.InstallContext;
import dev.verity.core.meta.LoaderType;
import dev.verity.core.net.Http;
import dev.verity.core.profile.Profile;
import dev.verity.core.report.Reports;
import dev.verity.core.util.Hashing;
import dev.verity.core.util.Json;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the whole pipeline (install from a pre-seeded home, launch, hook, reports) against fake game JVMs. */
class RunOrchestratorTest {
    @TempDir
    Path tmp;

    static final DisplayProvider NO_DISPLAY = new DisplayProvider() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public DisplaySession open(String targetId, Profile.Display settings) {
            return new DisplaySession() {
                @Override
                public Map<String, String> environment() {
                    return Map.of();
                }

                @Override
                public String description() {
                    return "test";
                }

                @Override
                public void close() {
                }
            };
        }
    };

    /** A client jar holding FakeGame and the verity-api classes. */
    Path clientJar() throws Exception {
        Path jar = tmp.resolve("client.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> c : List.of(FakeGame.class, Verity.class, Class.forName("dev.verity.api.Verity$Base64"))) {
                String name = c.getName().replace('.', '/') + ".class";
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = c.getClassLoader().getResourceAsStream(name)) {
                    in.transferTo(out);
                }
            }
        }
        return jar;
    }

    void seedVersion(VerityHome home, String id, Path client) throws Exception {
        Path dir = home.versions().resolve(id);
        Files.createDirectories(dir);
        Files.copy(client, dir.resolve(id + ".jar"));
        ObjectNode v = Json.MAPPER.createObjectNode();
        v.put("id", id);
        v.put("type", "release");
        v.put("mainClass", FakeGame.class.getName());
        v.put("minecraftArguments", "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory}");
        ObjectNode c = v.putObject("downloads").putObject("client");
        c.put("url", "https://invalid.example/client.jar");
        c.put("sha1", Hashing.sha1(client));
        c.put("size", Files.size(client));
        v.putArray("libraries");
        Files.write(dir.resolve(id + ".json"), Json.MAPPER.writeValueAsBytes(v));
    }

    @Test
    void runsClientsInParallelAndReports() throws Exception {
        VerityHome home = new VerityHome(tmp.resolve("home")).ensureCreated();
        GlobalSettings settings = new GlobalSettings();
        settings.javaOverrides.put(8, System.getProperty("java.home"));
        InstallContext ctx = new InstallContext(home, settings, new Http());
        Path client = clientJar();
        for (String id : List.of("fake-pass", "fake-fail", "fake-crash", "fake-quit", "fake-idle")) seedVersion(home, id, client);

        Path modJar = TestJars.jar(tmp.resolve("mods/universal.jar"), Map.of("a.txt", "x"));
        Profile p = new Profile();
        p.name = "e2e";
        p.source = tmp.resolve("e2e.json");
        p.mods.add(Profile.ModEntry.of(modJar.toString()));
        for (String id : List.of("fake-pass", "fake-fail", "fake-crash", "fake-quit", "fake-idle")) p.targets.add(Profile.TargetSpec.of(id, LoaderType.VANILLA));
        p.jvm.memoryMb = 512;
        p.test.timeoutSec = 60;
        p.test.startupTimeoutSec = 60;
        p.test.shutdownGraceSec = 1;
        p.test.vanillaSmokeSec = 4;

        RunOptions opts = new RunOptions();
        opts.displayProvider = NO_DISPLAY;
        opts.maxParallel = 5;
        opts.reportDir = tmp.resolve("report");
        Map<String, Status> last = Collections.synchronizedMap(new HashMap<>());
        List<String> lines = Collections.synchronizedList(new java.util.ArrayList<>());
        RunHandle handle = new RunOrchestrator(ctx).start(p, opts, new RunListener() {
            @Override
            public void targetStatus(Target target, Status status, String message) {
                last.put(target.id(), status);
            }

            @Override
            public void targetLog(Target target, String line) {
                lines.add(target.id() + " " + line);
            }
        });
        RunReport report = handle.report().get(120, TimeUnit.SECONDS);

        Map<String, TargetResult> byId = new HashMap<>();
        report.results.forEach(r -> byId.put(r.id, r));
        TargetResult pass = byId.get("fake-pass-vanilla");
        assertThat(pass.status).as(pass.message).isEqualTo(Status.PASSED);
        assertThat(pass.checks).extracting(CheckResult::name).containsExactly("mods-dir-exists", "loaded");
        assertThat(pass.verityLog).containsExactly("hello from fake-pass-vanilla");
        assertThat(pass.attachments).hasSize(1);
        assertThat(report.runDir).isNotNull();
        assertThat(Path.of(report.runDir).resolve(pass.attachments.get(0))).hasContent("attached");
        assertThat(pass.mods).isEmpty(); // vanilla targets get no mods

        TargetResult fail = byId.get("fake-fail-vanilla");
        assertThat(fail.status).isEqualTo(Status.FAILED);
        assertThat(fail.message).contains("1 of 2 checks failed");

        TargetResult crash = byId.get("fake-crash-vanilla");
        assertThat(crash.status).isEqualTo(Status.CRASHED);
        assertThat(crash.message).contains("Initializing game").contains("NoSuchMethodError");
        assertThat(crash.crashReports).hasSize(1);

        TargetResult quit = byId.get("fake-quit-vanilla");
        assertThat(quit.status).isEqualTo(Status.FAILED);
        assertThat(quit.message).contains("before calling Verity.finish()");

        TargetResult idle = byId.get("fake-idle-vanilla");
        assertThat(idle.status).as(idle.message).isEqualTo(Status.PASSED);
        assertThat(idle.message).contains("smoke");

        assertThat(last).containsEntry("fake-pass-vanilla", Status.PASSED);
        assertThat(lines).anyMatch(l -> l.contains("[Render thread/INFO] (Minecraft): Setting user: Verity"));
        // the sandboxed home is used
        assertThat(lines).anyMatch(l -> l.contains("user.home=" + home.instances().resolve("e2e").resolve("fake-pass-vanilla").resolve("home")));
        assertThat(report.success()).isFalse();

        Path dir = tmp.resolve("report");
        assertThat(dir.resolve(Reports.JSON)).exists();
        assertThat(dir.resolve(Reports.HTML)).content().contains("fake-pass-vanilla").contains("crashed");
        String junit = Files.readString(dir.resolve(Reports.JUNIT));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(junit.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(junit).contains("name=\"patched\"").contains("class was not transformed");
        assertThat(Reports.read(dir).results).hasSize(5);
    }

    private Profile fakeProfile(VerityHome home, String... ids) throws Exception {
        Path client = clientJar();
        Profile p = new Profile();
        p.name = "e2e";
        p.source = tmp.resolve("e2e.json");
        for (String id : ids) {
            seedVersion(home, id, client);
            p.targets.add(Profile.TargetSpec.of(id, LoaderType.VANILLA));
        }
        p.jvm.memoryMb = 512;
        p.test.timeoutSec = 60;
        p.test.startupTimeoutSec = 60;
        p.test.shutdownGraceSec = 1;
        p.test.vanillaSmokeSec = 4;
        return p;
    }

    private InstallContext context(VerityHome home) {
        GlobalSettings settings = new GlobalSettings();
        settings.javaOverrides.put(8, System.getProperty("java.home"));
        return new InstallContext(home, settings, new Http());
    }

    @Test
    void ignoresCrashReportsLeftInAKeptInstanceByAnEarlierRun() throws Exception {
        VerityHome home = new VerityHome(tmp.resolve("home")).ensureCreated();
        Profile p = fakeProfile(home, "fake-quit");
        Path old = home.instances().resolve("e2e").resolve("fake-quit-vanilla").resolve("game").resolve("crash-reports")
                .resolve("crash-old-client.txt");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "Description: from last week\n");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));

        RunOptions opts = new RunOptions();
        opts.displayProvider = NO_DISPLAY;
        opts.keepInstances = true;
        opts.reportDir = tmp.resolve("report");
        RunReport report = new RunOrchestrator(context(home)).start(p, opts, new RunListener() {
        }).report().get(120, TimeUnit.SECONDS);

        TargetResult quit = report.results.get(0);
        assertThat(quit.status).as(quit.message).isEqualTo(Status.FAILED);
        assertThat(quit.crashReports).isEmpty();
        assertThat(old).exists();
    }

    @Test
    void aThrowingListenerCannotHangTheRun() throws Exception {
        VerityHome home = new VerityHome(tmp.resolve("home")).ensureCreated();
        Profile p = fakeProfile(home, "fake-pass");
        RunOptions opts = new RunOptions();
        opts.displayProvider = NO_DISPLAY;
        opts.reportDir = tmp.resolve("report");
        RunReport report = new RunOrchestrator(context(home)).start(p, opts, new RunListener() {
            @Override
            public void targetStatus(Target target, Status status, String message) {
                if (status == Status.PASSED || status == Status.ERROR) throw new IllegalStateException("listener broke");
            }
        }).report().get(120, TimeUnit.SECONDS);

        TargetResult r = report.results.get(0);
        assertThat(r.status).isEqualTo(Status.ERROR);
        assertThat(r.message).contains("listener broke");
    }

    @Test
    void modrinthModsSkipTargetsWithoutFilesAndSmokeTestTheRest() throws Exception {
        try (dev.verity.core.modrinth.FakeModrinth fake = dev.verity.core.modrinth.FakeModrinth.essential(tmp.resolve("modrinth"))) {
            System.setProperty(dev.verity.core.modrinth.ModrinthClient.PROP_API, fake.base);
            try {
                VerityHome home = new VerityHome(tmp.resolve("home")).ensureCreated();
                GlobalSettings settings = new GlobalSettings();
                settings.javaOverrides.put(8, System.getProperty("java.home"));
                InstallContext ctx = new InstallContext(home, settings, new Http());
                seedVersion(home, "fake-idle", clientJar());
                // An offline Fabric install on top of it: loader list + profile JSON already in the caches.
                String fabricId = "fabric-loader-0.0.1-fake-idle";
                Path fabricJson = home.versions().resolve(fabricId).resolve(fabricId + ".json");
                Files.createDirectories(fabricJson.getParent());
                Files.writeString(fabricJson, "{\"id\":\"" + fabricId + "\",\"inheritsFrom\":\"fake-idle\",\"mainClass\":\""
                        + FakeGame.class.getName() + "\",\"libraries\":[]}");
                String loaderUrl = dev.verity.core.meta.FabricMeta.BASE + "/versions/loader/fake-idle";
                Files.writeString(home.metaCache().resolve(Hashing.sha1(loaderUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".cache"),
                        "[{\"loader\":{\"version\":\"0.0.1\",\"stable\":true}}]");

                Path universal = TestJars.jar(tmp.resolve("target/prometheus.jar"), Map.of("a.txt", "x"));
                Profile p = new Profile();
                p.name = "modrinth-e2e";
                p.source = tmp.resolve("p.json");
                p.mods.add(Profile.ModEntry.of(universal.toString()));
                p.mods.add(Profile.ModEntry.modrinth("essential"));
                p.targets.add(Profile.TargetSpec.of("fake-idle", LoaderType.FABRIC));
                p.targets.add(Profile.TargetSpec.of("fake-none", LoaderType.FABRIC));
                p.run.onlyTargetsWithAllMods = true;
                p.test.smokeSec = 4;
                p.test.timeoutSec = 60;
                p.test.startupTimeoutSec = 10; // must not apply while smoke testing
                p.test.shutdownGraceSec = 1;
                p.jvm.memoryMb = 512;

                RunOptions opts = new RunOptions();
                opts.displayProvider = NO_DISPLAY;
                opts.reportDir = tmp.resolve("report");
                List<String> lines = Collections.synchronizedList(new java.util.ArrayList<>());
                RunReport report = new RunOrchestrator(ctx).start(p, opts, new RunListener() {
                    @Override
                    public void targetLog(Target target, String line) {
                        lines.add(target.id() + " " + line);
                    }
                }).report().get(120, TimeUnit.SECONDS);

                Map<String, TargetResult> byId = new HashMap<>();
                report.results.forEach(r -> byId.put(r.id, r));
                TargetResult skipped = byId.get("fake-none-fabric");
                assertThat(skipped.status).isEqualTo(Status.SKIPPED);
                assertThat(skipped.message).contains("modrinth:essential");
                TargetResult ran = byId.get("fake-idle-fabric");
                assertThat(ran.status).as(ran.message).isEqualTo(Status.PASSED);
                assertThat(ran.message).contains("smoke test with 2 mods");
                assertThat(ran.mods).containsExactlyInAnyOrder("prometheus.jar", "essential-fabric-fake.jar");
                assertThat(lines).anyMatch(l -> l.contains("mods: essential-fabric-fake.jar prometheus.jar"));
                assertThat(report.success()).isTrue();
                assertThat(Files.readString(tmp.resolve("report").resolve(Reports.JUNIT))).contains("<skipped message=");
            } finally {
                System.clearProperty(dev.verity.core.modrinth.ModrinthClient.PROP_API);
            }
        }
    }
}

package dev.verity.core.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.verity.core.TestVersions;
import dev.verity.core.home.GlobalSettings;
import dev.verity.core.home.VerityHome;
import dev.verity.core.net.Http;
import dev.verity.core.util.Json;
import dev.verity.core.util.Progress;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeInstallerTest {
    @TempDir
    Path tmp;

    InstallContext context() throws IOException {
        VerityHome home = new VerityHome(tmp.resolve("home")).ensureCreated();
        GlobalSettings s = new GlobalSettings();
        s.javaOverrides.put(17, Path.of(System.getProperty("java.home")).toString());
        return new InstallContext(home, s, new Http());
    }

    static byte[] processorJar() throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeProcessor.class.getName());
        var bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, mf);
             InputStream cls = FakeProcessor.class.getResourceAsStream("FakeProcessor.class")) {
            jar.putNextEntry(new ZipEntry(FakeProcessor.class.getName().replace('.', '/') + ".class"));
            cls.transferTo(jar);
        }
        return bytes.toByteArray();
    }

    static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    @Test
    void modernInstallerRunsClientProcessors() throws Exception {
        InstallContext ctx = context();
        TestVersions.install(ctx.home().versions(), "1.20.1");
        Path installer = tmp.resolve("forge-installer.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(installer))) {
            put(zip, "install_profile.json", """
                    {"spec":1,"version":"1.20.1-forge-47.2.0","json":"/version.json","minecraft":"1.20.1",
                     "data":{
                       "PATCHED":{"client":"[net.minecraftforge:forge:1.20.1-47.2.0:client]","server":"[x:y:1:server]"},
                       "BINPATCH":{"client":"/data/client.lzma","server":"/data/server.lzma"},
                       "LIT":{"client":"'hello'","server":"'no'"}},
                     "processors":[
                       {"jar":"net.test:proc:1.0","classpath":[],"args":["--out","{PATCHED}","{BINPATCH}","{LIT}","{SIDE}","{MINECRAFT_VERSION}","[net.test:proc:1.0]"]},
                       {"sides":["server"],"jar":"net.test:missing:1.0","classpath":[],"args":[]}],
                     "libraries":[{"name":"net.test:proc:1.0","downloads":{"artifact":{"path":"net/test/proc/1.0/proc-1.0.jar","url":"","sha1":null,"size":-1}}}]}
                    """.getBytes(StandardCharsets.UTF_8));
            put(zip, "version.json", """
                    {"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
                     "libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.2.0:client","downloads":{"artifact":{"path":"net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar","url":""}}}]}
                    """.getBytes(StandardCharsets.UTF_8));
            put(zip, "data/client.lzma", "patches".getBytes(StandardCharsets.UTF_8));
            put(zip, "maven/net/test/proc/1.0/proc-1.0.jar", processorJar());
        }

        ForgeInstaller fi = new ForgeInstaller(ctx, new GameInstaller(ctx));
        String id;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            JsonNode profile = ForgeInstaller.readJson(zip, "install_profile.json");
            id = fi.installModern(zip, installer, profile, "1.20.1", Progress.NONE);
        }
        assertThat(id).isEqualTo("1.20.1-forge-47.2.0");
        assertThat(ctx.resolver().jsonPath(id)).exists();
        assertThat(ctx.home().libraries().resolve("net/test/proc/1.0/proc-1.0.jar")).exists();
        Path produced = ctx.home().libraries().resolve("net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar");
        String args = Files.readString(produced);
        java.util.List<String> lines = args.lines().toList();
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).endsWith("client.lzma");
        assertThat(Path.of(lines.get(0))).doesNotExist(); // work dir is cleaned up after processors ran
        assertThat(lines.subList(1, 5)).containsExactly("hello", "client", "1.20.1",
                ctx.home().libraries().resolve("net/test/proc/1.0/proc-1.0.jar").toString());
        assertThat(ctx.resolver().resolve(id).mainClass).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
    }

    @Test
    void legacyInstallerWritesVersionAndUniversalJar() throws Exception {
        InstallContext ctx = context();
        Path installer = tmp.resolve("legacy.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(installer))) {
            put(zip, "install_profile.json", """
                    {"install":{"target":"1.7.10-Forge10.13.4.1614-1.7.10","path":"net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10",
                                "filePath":"forge-1.7.10-10.13.4.1614-1.7.10-universal.jar","minecraft":"1.7.10"},
                     "versionInfo":{"id":"1.7.10-Forge10.13.4.1614-1.7.10","mainClass":"net.minecraft.launchwrapper.Launch",
                                    "minecraftArguments":"--tweakClass cpw.mods.fml.common.launcher.FMLTweaker","libraries":[]}}
                    """.getBytes(StandardCharsets.UTF_8));
            put(zip, "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar", "universal".getBytes(StandardCharsets.UTF_8));
        }
        ForgeInstaller fi = new ForgeInstaller(ctx, new GameInstaller(ctx));
        String id;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            id = fi.installLegacy(zip, ForgeInstaller.readJson(zip, "install_profile.json"), "1.7.10");
        }
        assertThat(id).isEqualTo("1.7.10-Forge10.13.4.1614-1.7.10");
        JsonNode v = Json.tree(ctx.resolver().jsonPath(id));
        assertThat(v.path("inheritsFrom").asText()).isEqualTo("1.7.10");
        assertThat(v.path("jar").asText()).isEqualTo("1.7.10");
        assertThat(ctx.home().libraries().resolve("net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10.jar"))
                .hasContent("universal");
    }

    @Test
    void tokens() throws Exception {
        Map<String, String> data = Map.of("A", "x", "B", "y");
        assertThat(ForgeInstaller.replaceTokens("{A}/{B}", data)).isEqualTo("x/y");
        assertThat(ForgeInstaller.replaceTokens("'{A}'", data)).isEqualTo("{A}");
        assertThat(ForgeInstaller.replaceTokens("a\\{b", data)).isEqualTo("a{b");
        assertThatThrownBy(() -> ForgeInstaller.replaceTokens("{C}", data)).isInstanceOf(IOException.class);
        assertThat(ForgeInstaller.appliesToClient(Json.tree("{\"sides\":[\"server\"]}"))).isFalse();
        assertThat(ForgeInstaller.appliesToClient(Json.tree("{}"))).isTrue();
    }
}

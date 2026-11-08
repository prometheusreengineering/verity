package ing.prometheusreengineer.verity.core.mods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import ing.prometheusreengineer.verity.core.util.Json;
import ing.prometheusreengineer.verity.core.util.McVersion;
import ing.prometheusreengineer.verity.core.util.VersionPredicate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/** Reads loader metadata from a mod jar without loading any of its classes. */
public final class JarInspector {
    private static final TomlMapper TOML = new TomlMapper();

    private JarInspector() {
    }

    public static ModInfo inspect(Path jar) throws IOException {
        Map<LoaderFamily, VersionPredicate> constraints = new EnumMap<>(LoaderFamily.class);
        String id = null;
        String name = null;
        String version = null;
        try (JarFile zip = new JarFile(jar.toFile(), false)) {
            JsonNode fabric = json(zip, "fabric.mod.json");
            if (fabric != null) {
                id = text(fabric, "id");
                name = text(fabric, "name");
                version = text(fabric, "version");
                constraints.put(LoaderFamily.FABRIC, fabricMinecraft(fabric.path("depends").path("minecraft")));
            }

            JsonNode neo = toml(zip, "META-INF/neoforge.mods.toml");
            if (neo != null) {
                ModsToml m = modsToml(neo);
                id = id != null ? id : m.id;
                name = name != null ? name : m.name;
                version = version != null ? version : m.version;
                constraints.put(LoaderFamily.NEOFORGE, m.minecraft);
            }
            JsonNode forge = toml(zip, "META-INF/mods.toml");
            if (forge != null) {
                ModsToml m = modsToml(forge);
                id = id != null ? id : m.id;
                name = name != null ? name : m.name;
                version = version != null ? version : m.version;
                if (m.dependsOnNeoForge && !m.dependsOnForge) constraints.putIfAbsent(LoaderFamily.NEOFORGE, m.minecraft);
                else constraints.put(LoaderFamily.FORGE_MODERN, m.minecraft);
            }

            JsonNode mcmod = json(zip, "mcmod.info");
            Manifest mf = zip.getManifest();
            Attributes attrs = mf == null ? null : mf.getMainAttributes();
            boolean legacyManifest = attrs != null && (attrs.getValue("TweakClass") != null || attrs.getValue("FMLCorePlugin") != null);
            if (mcmod != null || legacyManifest) {
                VersionPredicate p = VersionPredicate.ANY;
                JsonNode first = mcmodFirst(mcmod);
                if (first != null) {
                    id = id != null ? id : text(first, "modid");
                    name = name != null ? name : text(first, "name");
                    version = version != null ? version : text(first, "version");
                    String mc = text(first, "mcversion");
                    if (mc != null && McVersion.isRelease(mc.trim())) p = VersionPredicate.parse(mc.trim());
                }
                constraints.put(LoaderFamily.FORGE_LEGACY, p);
            }
        }
        return new ModInfo(jar, id, name, version, constraints);
    }

    record ModsToml(String id, String name, String version, VersionPredicate minecraft, boolean dependsOnForge, boolean dependsOnNeoForge) {
    }

    static ModsToml modsToml(JsonNode toml) {
        JsonNode mod = toml.path("mods").isArray() && !toml.path("mods").isEmpty() ? toml.path("mods").get(0) : null;
        String id = mod != null ? text(mod, "modId") : null;
        String name = mod != null ? text(mod, "displayName") : null;
        String version = mod != null ? text(mod, "version") : null;
        VersionPredicate mc = VersionPredicate.ANY;
        boolean forge = false;
        boolean neoforge = false;
        JsonNode deps = toml.path("dependencies");
        List<JsonNode> all = new ArrayList<>();
        if (deps.isObject()) deps.properties().forEach(e -> {
            if (e.getValue().isArray()) e.getValue().forEach(all::add);
        });
        else if (deps.isArray()) deps.forEach(all::add);
        for (JsonNode d : all) {
            String depId = text(d, "modId");
            if (depId == null) continue;
            switch (depId) {
                case "minecraft" -> {
                    String range = text(d, "versionRange");
                    if (range != null && !range.contains("$")) mc = VersionPredicate.parseMaven(range);
                }
                case "forge" -> forge = true;
                case "neoforge" -> neoforge = true;
                default -> {
                }
            }
        }
        String loader = text(toml, "modLoader");
        if (!forge && !neoforge && "javafml".equals(loader)) forge = true;
        return new ModsToml(id, name, version, mc, forge, neoforge);
    }

    static VersionPredicate fabricMinecraft(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return VersionPredicate.ANY;
        if (node.isArray()) {
            List<String> specs = new ArrayList<>();
            node.forEach(n -> specs.add(n.asText()));
            return VersionPredicate.anyOf(specs);
        }
        return VersionPredicate.parse(node.asText());
    }

    private static JsonNode mcmodFirst(JsonNode mcmod) {
        if (mcmod == null) return null;
        if (mcmod.isArray()) return mcmod.isEmpty() ? null : mcmod.get(0);
        JsonNode list = mcmod.path("modList");
        if (list.isArray() && !list.isEmpty()) return list.get(0);
        return null;
    }

    private static JsonNode json(JarFile zip, String name) {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return Json.MAPPER.readTree(in);
        } catch (IOException | RuntimeException ex) {
            return null; // malformed metadata (often with build placeholders) is treated as absent
        }
    }

    private static JsonNode toml(JarFile zip, String name) {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return TOML.readTree(in);
        } catch (IOException | RuntimeException ex) {
            return Json.MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

package ing.prometheusreengineer.verity.core.modrinth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import ing.prometheusreengineer.verity.core.net.Http;
import ing.prometheusreengineer.verity.core.net.MetaCache;
import ing.prometheusreengineer.verity.core.profile.ProfileException;
import ing.prometheusreengineer.verity.core.util.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only Modrinth API v2 client. Responses go through {@link MetaCache}, so cached projects work offline. */
public final class ModrinthClient {
    public static final String API = "https://api.modrinth.com/v2/";
    /** Overrides the API base URL (tests, mirrors). */
    public static final String PROP_API = "verity.modrinth.api";

    private final MetaCache cache;
    private final String base;
    private final Map<String, ModrinthProject> projects = new ConcurrentHashMap<>();
    private final Map<String, List<ModrinthVersion>> versions = new ConcurrentHashMap<>();

    public ModrinthClient(MetaCache cache) {
        this(cache, System.getProperty(PROP_API, API));
    }

    public ModrinthClient(MetaCache cache, String base) {
        this.cache = cache;
        this.base = base.endsWith("/") ? base : base + "/";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String get(String path, String what) throws IOException, ProfileException {
        try {
            return cache.get(base + path);
        } catch (Http.HttpStatusException e) {
            if (e.status() == 404) throw new ProfileException("Modrinth has no " + what);
            throw e;
        }
    }

    public ModrinthProject project(String idOrSlug) throws IOException, ProfileException {
        String key = idOrSlug.trim().toLowerCase();
        ModrinthProject p = projects.get(key);
        if (p == null) {
            p = Json.MAPPER.readValue(get("project/" + enc(idOrSlug), "project '" + idOrSlug + "'"), ModrinthProject.class);
            projects.put(key, p);
            if (p.id != null) projects.put(p.id.toLowerCase(), p);
            if (p.slug != null) projects.put(p.slug.toLowerCase(), p);
        }
        return p;
    }

    /** Every version of a project, newest first. */
    public List<ModrinthVersion> versions(String idOrSlug) throws IOException, ProfileException {
        String id = project(idOrSlug).id; // ids are case-sensitive; only the cache key is normalized
        String key = id.toLowerCase();
        List<ModrinthVersion> v = versions.get(key);
        if (v == null) {
            v = new ArrayList<>(Json.MAPPER.readValue(get("project/" + enc(id) + "/version", "versions for '" + idOrSlug + "'"),
                    new TypeReference<List<ModrinthVersion>>() {}));
            v.sort((a, b) -> b.datePublished.compareTo(a.datePublished));
            versions.put(key, v);
        }
        return v;
    }

    public ModrinthVersion version(String versionId) throws IOException, ProfileException {
        return Json.MAPPER.readValue(cache.getImmutable(base + "version/" + enc(versionId)), ModrinthVersion.class);
    }

    public List<ModrinthProject.Hit> search(String query, int limit) throws IOException, ProfileException {
        String facets = enc("[[\"project_type:mod\"]]");
        JsonNode n = Json.tree(get("search?query=" + enc(query) + "&limit=" + limit + "&facets=" + facets, "search results"));
        List<ModrinthProject.Hit> out = new ArrayList<>();
        for (JsonNode h : n.path("hits")) out.add(Json.MAPPER.treeToValue(h, ModrinthProject.Hit.class));
        return out;
    }
}

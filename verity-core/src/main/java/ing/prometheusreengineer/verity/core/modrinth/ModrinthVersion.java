package ing.prometheusreengineer.verity.core.modrinth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One published version of a Modrinth project ({@code GET /v2/project/{id}/version}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthVersion {
    public String id;
    @JsonProperty("project_id")
    public String projectId;
    public String name;
    @JsonProperty("version_number")
    public String versionNumber;
    /** release, beta or alpha. */
    @JsonProperty("version_type")
    public String versionType = "release";
    @JsonProperty("date_published")
    public String datePublished = "";
    @JsonProperty("game_versions")
    public List<String> gameVersions = new ArrayList<>();
    public List<String> loaders = new ArrayList<>();
    public List<File> files = new ArrayList<>();
    public List<Dependency> dependencies = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class File {
        public String url;
        public String filename;
        public boolean primary;
        public long size = -1;
        public Map<String, String> hashes = Map.of();

        public String sha1() {
            return hashes == null ? null : hashes.get("sha1");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dependency {
        @JsonProperty("version_id")
        public String versionId;
        @JsonProperty("project_id")
        public String projectId;
        /** required, optional, incompatible or embedded. */
        @JsonProperty("dependency_type")
        public String dependencyType;
    }

    /** The primary jar, else the first jar. */
    public File primaryFile() {
        File first = null;
        for (File f : files) {
            if (f.filename == null || !f.filename.toLowerCase().endsWith(".jar")) continue;
            if (f.primary) return f;
            if (first == null) first = f;
        }
        return first;
    }
}

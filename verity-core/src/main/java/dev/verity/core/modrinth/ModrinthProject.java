package dev.verity.core.modrinth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthProject {
    public String id;
    public String slug;
    public String title;
    public String description;
    @JsonProperty("project_type")
    public String projectType;
    public long downloads;
    public List<String> loaders = new ArrayList<>();
    @JsonProperty("game_versions")
    public List<String> gameVersions = new ArrayList<>();

    /** A search hit ({@code GET /v2/search}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hit {
        @JsonProperty("project_id")
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String author;
        public long downloads;
        @JsonProperty("project_type")
        public String projectType;
    }
}

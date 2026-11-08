package ing.prometheusreengineer.verity.core.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionManifest {
    public Latest latest;
    public List<Entry> versions = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Latest {
        public String release;
        public String snapshot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public String id;
        public String type;
        public String url;
        public String sha1;
        public String releaseTime;
    }

    public Optional<Entry> find(String id) {
        return versions.stream().filter(e -> e.id.equals(id)).findFirst();
    }

    /** Release ids, newest first (manifest order). */
    public List<String> releases() {
        return versions.stream().filter(e -> "release".equals(e.type)).map(e -> e.id).toList();
    }
}

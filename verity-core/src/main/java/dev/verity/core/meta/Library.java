package dev.verity.core.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.verity.core.util.MavenCoordinate;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Library {
    public String name;
    /** Maven repository base (Fabric, legacy Forge). */
    public String url;
    public Downloads downloads;
    public List<Rule> rules;
    /** Legacy natives: os name → classifier, possibly containing {@code ${arch}}. */
    public Map<String, String> natives;
    public Extract extract;
    /** Legacy Forge: whether the client needs this library. */
    public Boolean clientreq;
    public Boolean serverreq;
    /** Fabric meta. */
    public String sha1;
    public Long size;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Downloads {
        public VersionJson.DownloadInfo artifact;
        public Map<String, VersionJson.DownloadInfo> classifiers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extract {
        public List<String> exclude;
    }

    public MavenCoordinate coordinate() {
        return MavenCoordinate.parse(name);
    }

    public boolean hasNatives() {
        return natives != null && !natives.isEmpty();
    }

    /** True for LWJGL3-era entries like {@code org.lwjgl:lwjgl:3.3.1:natives-linux}. */
    public boolean isNativesClassifierArtifact() {
        MavenCoordinate c = coordinate();
        return c.classifier() != null && c.classifier().startsWith("natives-");
    }

    @Override
    public String toString() {
        return name;
    }
}

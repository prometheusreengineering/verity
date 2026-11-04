package dev.verity.core.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mojang launcher version JSON (also used by Fabric profiles and Forge/NeoForge {@code version.json}). Fields not
 * needed for launching are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionJson {
    public String id;
    public String inheritsFrom;
    public String type;
    public String mainClass;
    /** Pre-1.13 space separated game arguments. */
    public String minecraftArguments;
    public Arguments arguments;
    public AssetIndexRef assetIndex;
    public String assets;
    public String jar;
    public String releaseTime;
    public Map<String, DownloadInfo> downloads;
    public List<Library> libraries = new ArrayList<>();
    public JavaVersion javaVersion;
    public Logging logging;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Arguments {
        public List<Argument> game = new ArrayList<>();
        public List<Argument> jvm = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetIndexRef {
        public String id;
        public String url;
        public String sha1;
        public long size = -1;
        public long totalSize;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DownloadInfo {
        public String path;
        public String url;
        public String sha1;
        public long size = -1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JavaVersion {
        public String component;
        public int majorVersion;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Logging {
        public LoggingEntry client;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoggingEntry {
        public String argument;
        public FileRef file;
        public String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileRef extends DownloadInfo {
        public String id;
    }
}

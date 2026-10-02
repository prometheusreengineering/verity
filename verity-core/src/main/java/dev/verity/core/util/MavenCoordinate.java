package dev.verity.core.util;

/**
 * Maven coordinate in Mojang/Forge notation: {@code group:artifact:version[:classifier][@extension]}.
 */
public record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {

    public static MavenCoordinate parse(String spec) {
        String s = spec.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        String ext = "jar";
        int at = s.indexOf('@');
        if (at >= 0) {
            ext = s.substring(at + 1);
            s = s.substring(0, at);
        }
        String[] parts = s.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("Bad maven coordinate: " + spec);
        String classifier = parts.length > 3 ? parts[3] : null;
        return new MavenCoordinate(parts[0], parts[1], parts[2], classifier, ext);
    }

    /** Repository-relative path using {@code /} separators. */
    public String path() {
        StringBuilder b = new StringBuilder();
        b.append(group.replace('.', '/')).append('/').append(artifact).append('/').append(version).append('/');
        b.append(fileName());
        return b.toString();
    }

    public String fileName() {
        return artifact + "-" + version + (classifier != null ? "-" + classifier : "") + "." + extension;
    }

    public MavenCoordinate withClassifier(String newClassifier) {
        return new MavenCoordinate(group, artifact, version, newClassifier, extension);
    }

    /** Identity used to dedupe libraries across inherited version JSONs (version excluded). */
    public String versionlessKey() {
        return group + ":" + artifact + (classifier != null ? ":" + classifier : "");
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version + (classifier != null ? ":" + classifier : "")
                + ("jar".equals(extension) ? "" : "@" + extension);
    }
}

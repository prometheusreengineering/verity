package ing.prometheusreengineer.verity.core.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal maven-metadata.xml reader. */
public final class MavenMetadata {
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    private MavenMetadata() {
    }

    /** Versions in file order (Maven lists oldest first). */
    public static List<String> versions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = VERSION.matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}

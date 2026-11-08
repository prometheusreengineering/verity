package ing.prometheusreengineer.verity.core.home;

import ing.prometheusreengineer.verity.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-wide settings stored in {@code <home>/settings.json}. */
public class GlobalSettings {
    /** Max clients running at once; 0 = automatic (CPU and free memory based). */
    public int maxParallel = 0;
    /** Max concurrent HTTP downloads. */
    public int downloadThreads = 16;
    /** Java executables to use instead of downloaded runtimes, keyed by Java major version. */
    public Map<Integer, String> javaOverrides = new LinkedHashMap<>();
    /** How long version lists are cached before refetching. */
    public int metadataTtlMinutes = 60;
    /** OAuth client id for Microsoft login (live.com device-code flow). */
    public String msaClientId = "00000000402b5328";

    public static GlobalSettings load(VerityHome home) {
        try {
            if (Files.exists(home.settingsFile())) return Json.read(home.settingsFile(), GlobalSettings.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + home.settingsFile() + ": " + e.getMessage());
        }
        return new GlobalSettings();
    }

    public void save(VerityHome home) throws IOException {
        Json.write(home.settingsFile(), this);
    }
}

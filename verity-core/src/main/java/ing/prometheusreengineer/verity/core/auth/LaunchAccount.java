package ing.prometheusreengineer.verity.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The identity handed to the game. */
public record LaunchAccount(String name, UUID uuid, String accessToken, String userType, String xuid) {

    /** Offline account with the same UUID the vanilla server derives for offline players. */
    public static LaunchAccount offline(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new LaunchAccount(name, uuid, "0", "legacy", "0");
    }

    public String uuidNoDashes() {
        return uuid.toString().replace("-", "");
    }

    public boolean online() {
        return "msa".equals(userType);
    }
}

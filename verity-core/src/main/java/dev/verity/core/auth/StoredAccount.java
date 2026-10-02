package dev.verity.core.auth;

/** A Microsoft account remembered in {@code <home>/accounts/accounts.json}. */
public class StoredAccount {
    /** Minecraft profile UUID (dashed). */
    public String id;
    public String name;
    public String clientId;
    public String msRefreshToken;
    public String minecraftToken;
    /** Epoch millis. */
    public long minecraftTokenExpiresAt;
    public String xuid;
}

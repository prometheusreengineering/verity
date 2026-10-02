package dev.verity.core.auth;

import dev.verity.core.install.InstallContext;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Turns a profile's account setting into a {@link LaunchAccount}, refreshing Microsoft tokens as needed. */
public final class AccountService {
    /** CI: a Microsoft refresh token supplied as a secret instead of a stored account. */
    public static final String ENV_REFRESH_TOKEN = "VERITY_MSA_REFRESH_TOKEN";

    private final InstallContext ctx;
    private final AccountStore store;

    public AccountService(InstallContext ctx) {
        this.ctx = ctx;
        this.store = new AccountStore(ctx.home());
    }

    public AccountStore store() {
        return store;
    }

    public MicrosoftAuth microsoft() {
        return new MicrosoftAuth(ctx.http(), ctx.settings().msaClientId);
    }

    public synchronized LaunchAccount resolve(Profile.Account account) throws IOException, ProfileException {
        String type = account == null || account.type == null ? "offline" : account.type.trim().toLowerCase();
        if (type.equals("offline")) {
            String name = account == null || account.username == null || account.username.isBlank() ? "Verity" : account.username.trim();
            if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                throw new ProfileException("Offline username '" + name + "' must be 3-16 letters, digits or underscores");
            }
            return LaunchAccount.offline(name);
        }
        if (!type.equals("msa") && !type.equals("microsoft")) throw new ProfileException("Unknown account type '" + account.type + "'");

        StoredAccount stored;
        String env = System.getenv(ENV_REFRESH_TOKEN);
        if (env != null && !env.isBlank() && (account.id == null || account.id.isBlank())) {
            StoredAccount seed = new StoredAccount();
            seed.msRefreshToken = env.trim();
            stored = microsoft().refresh(seed);
        } else {
            stored = pick(account.id);
            if (stored.minecraftToken == null || stored.minecraftTokenExpiresAt < System.currentTimeMillis() + 10 * 60_000L) {
                stored = new MicrosoftAuth(ctx.http(), stored.clientId != null ? stored.clientId : ctx.settings().msaClientId).refresh(stored);
                store.put(stored);
            }
        }
        return new LaunchAccount(stored.name, UUID.fromString(stored.id), stored.minecraftToken, "msa", stored.xuid);
    }

    private StoredAccount pick(String id) throws IOException, ProfileException {
        if (id != null && !id.isBlank()) {
            return store.find(id).orElseThrow(() -> new ProfileException("No stored Microsoft account '" + id
                    + "'. Sign in with: verity account login"));
        }
        List<StoredAccount> all = store.list();
        if (all.size() == 1) return all.get(0);
        if (all.isEmpty()) throw new ProfileException("Profile uses a Microsoft account but none is signed in. Run: verity account login");
        throw new ProfileException("Several Microsoft accounts are stored; set account.id in the profile");
    }
}

package dev.verity.core.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.verity.core.home.VerityHome;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists Microsoft accounts. The file holds refresh tokens, so it is created owner-readable only where supported. */
public final class AccountStore {
    private final Path file;

    public AccountStore(VerityHome home) {
        this.file = home.accounts().resolve("accounts.json");
    }

    public synchronized List<StoredAccount> list() throws IOException {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        return Json.MAPPER.readValue(file.toFile(), new TypeReference<List<StoredAccount>>() {});
    }

    public synchronized Optional<StoredAccount> find(String idOrName) throws IOException {
        for (StoredAccount a : list()) {
            if (a.id.equalsIgnoreCase(idOrName) || a.id.replace("-", "").equalsIgnoreCase(idOrName) || a.name.equalsIgnoreCase(idOrName)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    public synchronized void put(StoredAccount account) throws IOException {
        List<StoredAccount> all = list();
        all.removeIf(a -> a.id.equals(account.id));
        all.add(account);
        write(all);
    }

    public synchronized boolean remove(String idOrName) throws IOException {
        List<StoredAccount> all = list();
        boolean removed = all.removeIf(a -> a.id.equalsIgnoreCase(idOrName) || a.name.equalsIgnoreCase(idOrName));
        if (removed) write(all);
        return removed;
    }

    private void write(List<StoredAccount> all) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the per-user LOCALAPPDATA ACL already restricts access
            }
        }
        Files.write(file, Json.MAPPER.writeValueAsBytes(all));
    }
}

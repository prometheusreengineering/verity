package dev.verity.ui;

import dev.verity.core.home.VerityHome;
import dev.verity.core.install.InstallContext;
import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileStore;
import dev.verity.core.run.RunOrchestrator;
import java.io.IOException;
import java.util.Comparator;
import javafx.animation.PauseTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Duration;

/** UI state shared by all views: services, the profile list, the selected profile, and autosave. */
final class AppModel {
    final VerityHome home;
    final InstallContext ctx;
    final ProfileStore store;
    final RunOrchestrator orchestrator;
    final ObservableList<Profile> profiles = FXCollections.observableArrayList();
    final ObjectProperty<Profile> selected = new SimpleObjectProperty<>();
    /** Bumped whenever the selected profile was edited, so dependent views can refresh. */
    final SimpleObjectProperty<Object> edits = new SimpleObjectProperty<>(new Object());
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(600));
    private Runnable onSaveError = () -> { };
    private Throwable lastSaveError;

    AppModel(String homeOverride) throws IOException {
        home = VerityHome.resolve(homeOverride);
        ctx = InstallContext.open(home);
        store = new ProfileStore(home);
        orchestrator = new RunOrchestrator(ctx);
        saveDelay.setOnFinished(e -> saveNow());
        reload();
    }

    void reload() throws IOException {
        Profile keep = selected.get();
        profiles.setAll(store.list());
        if (keep != null) {
            profiles.stream().filter(p -> p.name.equals(keep.name)).findFirst().ifPresent(selected::set);
        }
    }

    /** Call after editing the selected profile; saves shortly after the last change. */
    void changed() {
        edits.set(new Object());
        saveDelay.playFromStart();
    }

    void saveNow() {
        Profile p = selected.get();
        if (p == null) return;
        try {
            store.save(p);
        } catch (IOException e) {
            lastSaveError = e;
            onSaveError.run();
        }
    }

    void onSaveError(Runnable r) {
        onSaveError = r;
    }

    Throwable lastSaveError() {
        return lastSaveError;
    }

    Profile create(String name) throws IOException {
        Profile p = new Profile();
        p.name = uniqueName(name);
        store.save(p);
        profiles.add(p);
        profiles.sort(Comparator.comparing(x -> x.name.toLowerCase()));
        selected.set(p);
        return p;
    }

    String uniqueName(String base) {
        String name = base;
        int i = 2;
        while (true) {
            String n = name;
            if (profiles.stream().noneMatch(p -> p.name.equalsIgnoreCase(n))) return name;
            name = base + " " + i++;
        }
    }
}

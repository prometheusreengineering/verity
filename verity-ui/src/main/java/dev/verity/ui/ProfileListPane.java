package dev.verity.ui;

import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileStore;
import java.io.File;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Left sidebar listing profiles. */
final class ProfileListPane {
    private final AppModel model;
    private final Stage stage;
    private final VBox root = new VBox();
    private final ListView<Profile> list = new ListView<>();

    ProfileListPane(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
        Label title = new Label("Profiles");
        title.getStyleClass().add("sidebar-title");

        list.setItems(model.profiles);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Profile p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    return;
                }
                setText(p.name);
                setStyle("-fx-padding: 8 10;");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b != model.selected.get()) {
                model.saveNow();
                model.selected.set(b);
            }
        });
        model.selected.addListener((o, a, b) -> {
            if (b != null && list.getSelectionModel().getSelectedItem() != b) list.getSelectionModel().select(b);
        });
        model.edits.addListener((o, a, b) -> list.refresh());
        VBox.setVgrow(list, Priority.ALWAYS);

        Button add = new Button("New");
        add.setOnAction(e -> newProfile());
        Button dup = new Button("Duplicate");
        dup.setOnAction(e -> duplicate());
        Button rename = new Button("Rename");
        rename.setOnAction(e -> rename());
        Button del = new Button("Delete");
        del.getStyleClass().add("danger");
        del.setOnAction(e -> delete());
        Button imp = new Button("Import…");
        imp.setOnAction(e -> importProfile());
        Button exp = new Button("Export…");
        exp.setOnAction(e -> exportProfile());
        for (Button b : new Button[] {dup, rename, del, exp}) b.disableProperty().bind(model.selected.isNull());
        FlowPane buttons = new FlowPane(6, 6, add, dup, rename, del, imp, exp);

        ContextMenu menu = new ContextMenu();
        MenuItem mDup = new MenuItem("Duplicate");
        mDup.setOnAction(e -> duplicate());
        MenuItem mRen = new MenuItem("Rename");
        mRen.setOnAction(e -> rename());
        MenuItem mDel = new MenuItem("Delete");
        mDel.setOnAction(e -> delete());
        menu.getItems().addAll(mDup, mRen, mDel);
        list.setContextMenu(menu);

        root.getChildren().addAll(title, list, buttons);
        root.getStyleClass().add("sidebar");
        root.setPadding(new Insets(10));
        if (!model.profiles.isEmpty()) list.getSelectionModel().select(0);
    }

    Parent root() {
        return root;
    }

    private void newProfile() {
        TextInputDialog d = new TextInputDialog(model.uniqueName("New profile"));
        d.initOwner(stage);
        d.setTitle("New profile");
        d.setHeaderText("Name the profile (e.g. the mod you are testing)");
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            try {
                model.create(name);
            } catch (Exception e) {
                Fx.error(stage, "Could not create profile", e);
            }
        });
    }

    private void duplicate() {
        Profile p = model.selected.get();
        if (p == null) return;
        try {
            model.saveNow();
            Profile c = ProfileStore.copy(p);
            c.name = model.uniqueName(p.name + " copy");
            model.store.save(c);
            model.profiles.add(c);
            model.selected.set(c);
        } catch (Exception e) {
            Fx.error(stage, "Could not duplicate profile", e);
        }
    }

    private void rename() {
        Profile p = model.selected.get();
        if (p == null) return;
        TextInputDialog d = new TextInputDialog(p.name);
        d.initOwner(stage);
        d.setTitle("Rename profile");
        d.setHeaderText("New name");
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty() && !s.equals(p.name)).ifPresent(name -> {
            try {
                model.store.rename(p, model.uniqueName(name));
                model.changed();
            } catch (Exception e) {
                Fx.error(stage, "Could not rename profile", e);
            }
        });
    }

    private void delete() {
        Profile p = model.selected.get();
        if (p == null) return;
        if (!Fx.confirm(stage, "Delete profile '" + p.name + "'?", "This removes the profile file. Mods and reports are not touched.")) return;
        try {
            model.store.delete(p);
            model.profiles.remove(p);
            model.selected.set(model.profiles.isEmpty() ? null : model.profiles.get(0));
        } catch (Exception e) {
            Fx.error(stage, "Could not delete profile", e);
        }
    }

    private void importProfile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import profile");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Verity profile", "*.json"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            Profile p = model.store.importProfile(f.toPath());
            model.reload();
            model.profiles.stream().filter(x -> x.name.equals(p.name)).findFirst().ifPresent(model.selected::set);
        } catch (Exception e) {
            Fx.error(stage, "Could not import " + f, e);
        }
    }

    private void exportProfile() {
        Profile p = model.selected.get();
        if (p == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Export profile");
        fc.setInitialFileName("verity.json");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            ProfileStore.saveTo(p, f.toPath());
        } catch (Exception e) {
            Fx.error(stage, "Could not export", e);
        }
    }
}

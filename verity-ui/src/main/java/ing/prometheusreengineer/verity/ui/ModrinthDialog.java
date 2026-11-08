package ing.prometheusreengineer.verity.ui;

import ing.prometheusreengineer.verity.core.modrinth.ModrinthProject;
import ing.prometheusreengineer.verity.core.modrinth.ModrinthVersion;
import ing.prometheusreengineer.verity.core.profile.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

/** Search Modrinth and turn a project into a mod entry (file picked per target at run time). */
final class ModrinthDialog {
    private final AppModel model;
    private final Stage stage;

    ModrinthDialog(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
    }

    Optional<Profile.ModEntry> show() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Add from Modrinth");
        d.setHeaderText("Verity picks the right file of this project for every target (newest build for that version and loader)");
        TextField query = new TextField();
        query.setPromptText("Search mods, or type a project slug/id (e.g. essential)");
        ListView<ModrinthProject.Hit> results = new ListView<>();
        results.setPrefHeight(220);
        results.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ModrinthProject.Hit h, boolean empty) {
                super.updateItem(h, empty);
                setText(empty || h == null ? null : h.title + "  (" + h.slug + ")  ·  " + String.format("%,d", h.downloads) + " downloads"
                        + (h.author != null ? "  ·  by " + h.author : ""));
            }
        });
        ComboBox<String> channel = new ComboBox<>(FXCollections.observableArrayList("release", "beta", "alpha"));
        channel.setValue("release");
        ComboBox<String> version = new ComboBox<>();
        version.setEditable(true);
        version.setPromptText("newest for each target");
        CheckBox deps = new CheckBox("Also install required dependencies");
        deps.setSelected(true);
        Label status = Fx.hint("");

        Runnable search = () -> {
            String q = query.getText().trim();
            if (q.isEmpty()) return;
            status.setText("Searching…");
            Fx.async(() -> model.ctx.modrinth().search(q, 15), hits -> {
                results.setItems(FXCollections.observableArrayList(hits));
                status.setText(hits.isEmpty() ? "No mods found" : hits.size() + " result(s)");
                if (!hits.isEmpty()) results.getSelectionModel().select(0);
            }, err -> status.setText("Search failed: " + err.getMessage()));
        };
        query.setOnAction(e -> search.run());
        results.getSelectionModel().selectedItemProperty().addListener((o, a, h) -> {
            version.getItems().clear();
            if (h == null) return;
            Fx.async(() -> model.ctx.modrinth().versions(h.projectId), vs -> {
                List<String> items = new ArrayList<>();
                for (ModrinthVersion v : vs) items.add(v.versionNumber);
                version.getItems().setAll(items.stream().distinct().toList());
                status.setText(vs.size() + " published versions");
            }, err -> status.setText("Could not load versions: " + err.getMessage()));
        });

        GridPane g = Fx.form();
        int r = Fx.row(g, 0, "Search", query, "Press Enter to search.");
        r = Fx.row(g, r, "Project", results, null);
        r = Fx.row(g, r, "Channel", channel, "release: stable builds only. beta/alpha also allow pre-release builds when they are newer.");
        r = Fx.row(g, r, "Pin version", version, "Leave empty to pick the newest build per target. A pin only runs on targets that version supports.");
        r = Fx.row(g, r, "", deps, null);
        status.setMaxWidth(560);
        g.add(status, 1, r);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(b -> b);
        Optional<ButtonType> res = d.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return Optional.empty();
        ModrinthProject.Hit hit = results.getSelectionModel().getSelectedItem();
        String project = hit != null ? hit.slug : query.getText().trim();
        if (project == null || project.isBlank()) return Optional.empty();
        Profile.ModEntry e = Profile.ModEntry.modrinth(project);
        e.channel = "release".equals(channel.getValue()) ? null : channel.getValue();
        String v = version.getEditor().getText();
        e.version = v == null || v.isBlank() ? null : v.trim();
        e.dependencies = deps.isSelected();
        return Optional.of(e);
    }
}

package ing.prometheusreengineer.verity.ui;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver;
import ing.prometheusreengineer.verity.core.mods.ModPlanner;
import ing.prometheusreengineer.verity.core.mods.ModResolver;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.run.RunOptions;
import ing.prometheusreengineer.verity.core.run.Target;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Mod jars for the profile, with a live preview of which jar goes to which target. */
final class ModsTab {
    private final AppModel model;
    private final Stage stage;
    private final SplitPane root = new SplitPane();
    private final TableView<Profile.ModEntry> table = new TableView<>();
    private final TableView<ModPlanner.TargetPlan> preview = new TableView<>();
    private final Label previewStatus = Fx.hint("");

    ModsTab(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
        table.setEditable(true);

        TableColumn<Profile.ModEntry, String> path = new TableColumn<>("Mod (path, folder, glob or Modrinth project)");
        path.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().fromModrinth()
                ? "Modrinth: " + c.getValue().modrinth + (c.getValue().version != null ? " @" + c.getValue().version : "")
                        + (c.getValue().channel != null && !"release".equals(c.getValue().channel) ? " [" + c.getValue().channel + "]" : "")
                : c.getValue().path));
        path.setCellFactory(col -> new TextFieldTableCell<>(new javafx.util.converter.DefaultStringConverter()) {
            @Override
            public void startEdit() {
                // Modrinth entries are edited through the Modrinth dialog, not as text.
                if (getTableRow() != null && getTableRow().getItem() != null && getTableRow().getItem().fromModrinth()) return;
                super.startEdit();
            }
        });
        path.setOnEditCommit(e -> {
            e.getRowValue().path = e.getNewValue();
            changed();
        });
        path.setPrefWidth(420);

        TableColumn<Profile.ModEntry, Profile.ModEntry> match = new TableColumn<>("Match");
        match.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        match.setCellFactory(c -> new TableCell<>() {
            private final ComboBox<Profile.Match> box = Fx.enumCombo(Profile.Match.values(), Profile.Match::id);

            @Override
            protected void updateItem(Profile.ModEntry m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setGraphic(null);
                    return;
                }
                box.setValue(m.match);
                box.setOnAction(e -> {
                    m.match = box.getValue();
                    changed();
                });
                box.setTooltip(new Tooltip("auto: decide from the jar's metadata · always: every modded target · never: disabled"));
                setGraphic(box);
            }
        });
        match.setPrefWidth(120);

        TableColumn<Profile.ModEntry, String> loaders = new TableColumn<>("Only loaders");
        loaders.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().loaders == null ? ""
                : c.getValue().loaders.stream().map(LoaderType::id).collect(Collectors.joining(","))));
        loaders.setCellFactory(TextFieldTableCell.forTableColumn());
        loaders.setOnEditCommit(e -> {
            List<LoaderType> list = new ArrayList<>();
            for (String s : e.getNewValue().split("[,\\s]+")) {
                if (s.isBlank()) continue;
                try {
                    list.add(LoaderType.parse(s));
                } catch (IllegalArgumentException ex) {
                    Fx.error(stage, "Unknown loader", ex);
                }
            }
            e.getRowValue().loaders = list;
            changed();
        });
        loaders.setPrefWidth(140);

        TableColumn<Profile.ModEntry, String> mc = new TableColumn<>("Only Minecraft");
        mc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().minecraft == null ? "" : c.getValue().minecraft));
        mc.setCellFactory(TextFieldTableCell.forTableColumn());
        mc.setOnEditCommit(e -> {
            e.getRowValue().minecraft = e.getNewValue().isBlank() ? null : e.getNewValue().trim();
            changed();
        });
        mc.setPrefWidth(140);
        table.getColumns().addAll(List.of(path, match, loaders, mc));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Drop mod jars or folders here, or use the buttons above."));
        table.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        table.setOnDragDropped(e -> {
            for (File f : e.getDragboard().getFiles()) add(f.getAbsolutePath());
            e.setDropCompleted(true);
            e.consume();
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button files = new Button("Add jars…");
        files.getStyleClass().add("accent");
        files.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mod jars", "*.jar"));
            List<File> chosen = fc.showOpenMultipleDialog(stage);
            if (chosen != null) chosen.forEach(f -> add(f.getAbsolutePath()));
        });
        Button folder = new Button("Add folder…");
        folder.setOnAction(e -> {
            File f = new DirectoryChooser().showDialog(stage);
            if (f != null) add(f.getAbsolutePath());
        });
        Button glob = new Button("Add pattern…");
        glob.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog("/path/to/project/build/libs/*.jar");
            d.initOwner(stage);
            d.setHeaderText("A glob is re-evaluated on every run, so rebuilt jars with new version numbers are picked up.");
            d.showAndWait().filter(s -> !s.isBlank()).ifPresent(s -> add(s.trim()));
        });
        Button fromModrinth = new Button("Add from Modrinth…");
        fromModrinth.setOnAction(e -> {
            Profile p = model.selected.get();
            if (p == null) return;
            new ModrinthDialog(model, stage).show().ifPresent(entry -> {
                p.mods.add(entry);
                refresh();
                changed();
            });
        });
        Button remove = new Button("Remove");
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            model.selected.get().mods.remove(table.getSelectionModel().getSelectedItem());
            refresh();
            changed();
        });
        Button refreshPreview = new Button("Refresh preview");
        refreshPreview.setOnAction(e -> updatePreview());

        VBox top = new VBox(8, Fx.hint("Rules (\"Only loaders\", \"Only Minecraft\") override metadata detection. Without rules, Verity reads "
                + "fabric.mod.json / mods.toml / neoforge.mods.toml / mcmod.info to install each jar only where it fits; "
                + "jars without metadata go everywhere. Relative paths resolve against the profile file."),
                new HBox(8, files, folder, glob, fromModrinth, remove), table);
        top.setPadding(new Insets(12));

        TableColumn<ModPlanner.TargetPlan, String> tcol = new TableColumn<>("Target");
        tcol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().target().id()));
        tcol.setPrefWidth(180);
        TableColumn<ModPlanner.TargetPlan, ModPlanner.TargetPlan> inc = new TableColumn<>("Installed jars");
        inc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        inc.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(ModPlanner.TargetPlan e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                Profile p = model.selected.get();
                boolean skip = p != null && p.run.onlyTargetsWithAllMods && !e.missingEntries().isEmpty();
                String jars = e.included().stream().map(ModPlanner.PlannedMod::fileName).collect(Collectors.joining(", "));
                String missing = e.missingEntries().isEmpty() ? "" : "nothing from " + String.join(", ", e.missingEntries());
                setText(skip ? "SKIPPED: " + missing : jars + (missing.isEmpty() ? "" : (jars.isEmpty() ? "" : "   ·   ") + missing));
                setStyle(skip ? "-fx-opacity: 0.55;" : "");
                setTooltip(new Tooltip(e.mods().stream().map(d -> (d.included() ? "+ " : "- ") + d.fileName() + "  (" + d.reason() + ")")
                        .collect(Collectors.joining("\n"))));
            }
        });
        inc.setPrefWidth(600);
        preview.getColumns().addAll(List.of(tcol, inc));
        preview.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        preview.setPlaceholder(new Label("Add targets and mods to see which jars each client gets."));
        VBox.setVgrow(preview, Priority.ALWAYS);
        Label ptitle = new Label("Assignment preview");
        ptitle.getStyleClass().add("section-title");
        VBox bottom = new VBox(6, new HBox(8, ptitle, Fx.spacer(), refreshPreview), previewStatus, preview);
        bottom.setPadding(new Insets(12));

        root.setOrientation(javafx.geometry.Orientation.VERTICAL);
        root.getItems().addAll(top, bottom);
        root.setDividerPositions(0.5);

        model.selected.addListener((o, a, b) -> {
            refresh();
            updatePreview();
        });
        model.edits.addListener((o, a, b) -> updatePreview());
        refresh();
        updatePreview();
    }

    Parent root() {
        return root;
    }

    private void changed() {
        model.changed();
    }

    private void add(String path) {
        Profile p = model.selected.get();
        if (p == null) return;
        if (p.mods.stream().anyMatch(m -> path.equals(m.path))) return;
        p.mods.add(Profile.ModEntry.of(path));
        refresh();
        changed();
    }

    private void refresh() {
        Profile p = model.selected.get();
        table.setItems(p == null ? FXCollections.observableArrayList() : FXCollections.observableArrayList(p.mods));
    }

    private void updatePreview() {
        Profile p = model.selected.get();
        if (p == null) {
            preview.getItems().clear();
            return;
        }
        previewStatus.setText("Calculating…");
        Fx.async(() -> {
            List<Target> targets = model.orchestrator.targets(p, new RunOptions());
            List<ModResolver.ModFile> mods = ModResolver.expand(p);
            return ModPlanner.plan(p, targets, mods, new ModrinthResolver(model.ctx.modrinth()), model.home);
        }, map -> {
            preview.setItems(FXCollections.observableArrayList(map.values()));
            long skipped = p.run.onlyTargetsWithAllMods ? map.values().stream().filter(t -> !t.missingEntries().isEmpty()).count() : 0;
            previewStatus.setText(map.size() + " target(s)" + (skipped > 0 ? ", " + skipped + " skipped because a mod has no file" : "")
                    + ". Hover a row to see why each jar is in or out.");
        }, err -> {
            preview.getItems().clear();
            previewStatus.setText(err.getMessage());
        });
    }
}

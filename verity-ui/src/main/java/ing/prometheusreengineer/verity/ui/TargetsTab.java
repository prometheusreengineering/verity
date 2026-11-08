package ing.prometheusreengineer.verity.ui;

import ing.prometheusreengineer.verity.core.meta.LoaderType;
import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.run.RunOptions;
import ing.prometheusreengineer.verity.core.run.Target;
import ing.prometheusreengineer.verity.core.run.TargetExpander;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Which Minecraft versions and loaders the profile runs. */
final class TargetsTab {
    private final AppModel model;
    private final Stage stage;
    private final VBox root = new VBox(10);
    private final TableView<Profile.TargetSpec> table = new TableView<>();

    TargetsTab(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
        root.setPadding(new Insets(12));

        TableColumn<Profile.TargetSpec, Profile.TargetSpec> on = new TableColumn<>("On");
        on.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        on.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            @Override
            protected void updateItem(Profile.TargetSpec t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(t.enabled);
                box.setOnAction(e -> {
                    t.enabled = box.isSelected();
                    model.changed();
                });
                setGraphic(box);
            }
        });
        on.setPrefWidth(50);
        TableColumn<Profile.TargetSpec, String> kind = col("Type", t -> t.select != null ? "Range" : "Version", 80);
        TableColumn<Profile.TargetSpec, String> mc = col("Minecraft", t -> t.select == null ? t.minecraft
                : (t.select.modrinth != null && !t.select.modrinth.isEmpty() ? "on Modrinth: " + String.join(", ", t.select.modrinth)
                        + ("*".equals(t.select.minecraft) ? "" : ", " + t.select.minecraft) : t.select.minecraft), 220);
        TableColumn<Profile.TargetSpec, String> loader = col("Loader", t -> t.select != null
                ? (t.select.loaders.isEmpty() ? "vanilla" : t.select.loaders.stream().map(LoaderType::displayName).collect(Collectors.joining(", ")))
                : (t.loader == null ? "Vanilla" : t.loader.displayName()), 200);
        TableColumn<Profile.TargetSpec, String> lv = col("Loader version", t -> {
            String v = t.select != null ? t.select.loaderVersion : t.loaderVersion;
            return v == null || v.isBlank() ? "recommended" : v;
        }, 140);
        TableColumn<Profile.TargetSpec, String> opts = col("Options", t -> t.select == null ? ""
                : (t.select.latestPatchOnly ? "latest patch of each minor" : "") + (t.select.limit > 0 ? (t.select.latestPatchOnly ? ", " : "") + "max " + t.select.limit : ""), 220);
        table.getColumns().addAll(List.of(on, kind, mc, loader, lv, opts));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No targets yet. Add Minecraft versions, or a version range that expands automatically."));
        table.setRowFactory(t -> {
            TableRow<Profile.TargetSpec> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) edit(row.getItem());
            });
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button addVersions = new Button("Add versions…");
        addVersions.getStyleClass().add("accent");
        addVersions.setOnAction(e -> addVersions());
        Button addRange = new Button("Add range…");
        addRange.setOnAction(e -> editSelector(null));
        Button edit = new Button("Edit…");
        edit.setOnAction(e -> {
            Profile.TargetSpec t = table.getSelectionModel().getSelectedItem();
            if (t != null) edit(t);
        });
        Button remove = new Button("Remove");
        remove.setOnAction(e -> {
            List<Profile.TargetSpec> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            model.selected.get().targets.removeAll(sel);
            refresh();
            model.changed();
        });
        Button preview = new Button("Preview targets");
        preview.setOnAction(e -> preview());
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        HBox buttons = new HBox(8, addVersions, addRange, edit, remove, Fx.spacer(), preview);
        root.getChildren().addAll(
                Fx.hint("Each target is one client launched in parallel. A range (e.g. \">=1.20\" with Fabric + NeoForge, latest patch only) "
                        + "expands to concrete versions at run time, so new Minecraft releases are picked up automatically."),
                buttons, table);
        model.selected.addListener((o, a, b) -> refresh());
        refresh();
    }

    private static TableColumn<Profile.TargetSpec, String> col(String name, java.util.function.Function<Profile.TargetSpec, String> f, double w) {
        TableColumn<Profile.TargetSpec, String> c = new TableColumn<>(name);
        c.setCellValueFactory(x -> new ReadOnlyObjectWrapper<>(f.apply(x.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    Parent root() {
        return root;
    }

    private void refresh() {
        Profile p = model.selected.get();
        table.setItems(p == null ? FXCollections.observableArrayList() : FXCollections.observableArrayList(p.targets));
    }

    private void edit(Profile.TargetSpec t) {
        if (t.select != null) editSelector(t);
        else editVersion(t);
    }

    /** Multi-select versions for one loader. */
    private void addVersions() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Add versions");
        d.setHeaderText("Pick a loader and one or more Minecraft versions");
        ComboBox<LoaderType> loader = Fx.enumCombo(LoaderType.values(), LoaderType::displayName);
        loader.setValue(LoaderType.FABRIC);
        ListView<String> versions = new ListView<>();
        versions.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        versions.setPrefHeight(320);
        TextField filter = new TextField();
        filter.setPromptText("Filter, e.g. 1.20");
        ComboBox<String> loaderVersion = new ComboBox<>(FXCollections.observableArrayList("recommended", "latest"));
        loaderVersion.setEditable(true);
        loaderVersion.setValue("recommended");
        Label status = Fx.hint("Loading…");
        List<String> all = new ArrayList<>();
        Runnable applyFilter = () -> versions.setItems(FXCollections.observableArrayList(
                all.stream().filter(v -> filter.getText().isBlank() || v.startsWith(filter.getText().trim())).toList()));
        Runnable load = () -> {
            status.setText("Loading versions…");
            LoaderType l = loader.getValue();
            loaderVersion.setDisable(l == LoaderType.VANILLA);
            Fx.async(() -> model.ctx.meta().gameVersions(l), list -> {
                all.clear();
                all.addAll(list);
                applyFilter.run();
                status.setText(list.size() + " versions (Ctrl/Shift-click to select several)");
            }, err -> status.setText("Could not load versions: " + err.getMessage()));
        };
        loader.setOnAction(e -> load.run());
        filter.textProperty().addListener((o, a, b) -> applyFilter.run());
        load.run();

        GridPane g = Fx.form();
        int r = 0;
        r = Fx.row(g, r, "Loader", loader, null);
        r = Fx.row(g, r, "Filter", filter, null);
        r = Fx.row(g, r, "Versions", versions, null);
        r = Fx.row(g, r, "Loader version", loaderVersion, "recommended = the loader's recommended/stable build for each version; latest = newest build; or an exact version.");
        g.add(status, 1, r);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            for (String v : versions.getSelectionModel().getSelectedItems()) {
                Profile.TargetSpec t = Profile.TargetSpec.of(v, loader.getValue());
                String lv = loaderVersion.getValue();
                if (loader.getValue() != LoaderType.VANILLA && lv != null && !lv.isBlank() && !lv.equals("recommended")) t.loaderVersion = lv.trim();
                model.selected.get().targets.add(t);
            }
            refresh();
            model.changed();
        });
    }

    private void editVersion(Profile.TargetSpec t) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Edit target");
        ComboBox<LoaderType> loader = Fx.enumCombo(LoaderType.values(), LoaderType::displayName);
        loader.setValue(t.loader == null ? LoaderType.VANILLA : t.loader);
        ComboBox<String> mc = new ComboBox<>();
        mc.setEditable(true);
        mc.setValue(t.minecraft);
        ComboBox<String> lv = new ComboBox<>(FXCollections.observableArrayList("recommended", "latest"));
        lv.setEditable(true);
        lv.setValue(t.loaderVersion == null ? "recommended" : t.loaderVersion);
        Runnable loadMc = () -> Fx.async(() -> model.ctx.meta().gameVersions(loader.getValue()),
                list -> mc.getItems().setAll(list), err -> { });
        Runnable loadLv = () -> {
            if (loader.getValue() == LoaderType.VANILLA || mc.getValue() == null) return;
            Fx.async(() -> model.ctx.meta().loader(loader.getValue()).loaderVersions(mc.getValue()), list -> {
                List<String> items = new ArrayList<>(List.of("recommended", "latest"));
                items.addAll(list);
                lv.getItems().setAll(items);
            }, err -> { });
        };
        loader.setOnAction(e -> {
            loadMc.run();
            loadLv.run();
        });
        mc.valueProperty().addListener((o, a, b) -> loadLv.run());
        loadMc.run();
        loadLv.run();
        GridPane g = Fx.form();
        int r = Fx.row(g, 0, "Loader", loader, null);
        r = Fx.row(g, r, "Minecraft", mc, "A release id like 1.20.1, or 'latest'.");
        Fx.row(g, r, "Loader version", lv, null);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            t.loader = loader.getValue();
            t.minecraft = mc.getEditor().getText().trim();
            String v = lv.getEditor().getText().trim();
            t.loaderVersion = v.isEmpty() || v.equals("recommended") || t.loader == LoaderType.VANILLA ? null : v;
            refresh();
            model.changed();
        });
    }

    private void editSelector(Profile.TargetSpec existing) {
        Profile.TargetSpec t = existing != null ? existing : new Profile.TargetSpec();
        if (t.select == null) t.select = new Profile.Selector();
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle(existing == null ? "Add range" : "Edit range");
        d.setHeaderText("A version range expands to every matching release for each selected loader");
        TextField range = new TextField(t.select.minecraft);
        range.setPromptText(">=1.20, 1.20.x, [1.8.9,1.12.2], latest, *");
        List<CheckBox> loaders = new ArrayList<>();
        HBox loaderBox = new HBox(12);
        for (LoaderType l : LoaderType.values()) {
            CheckBox cb = new CheckBox(l.displayName());
            cb.setUserData(l);
            cb.setSelected(t.select.loaders.contains(l));
            loaders.add(cb);
            loaderBox.getChildren().add(cb);
        }
        CheckBox latestPatch = new CheckBox("Only the newest patch of each minor version (1.20.6, 1.21.10, …)");
        latestPatch.setSelected(t.select.latestPatchOnly);
        Spinner<Integer> limit = new Spinner<>(0, 200, t.select.limit);
        limit.setEditable(true);
        TextField lv = new TextField(t.select.loaderVersion == null ? "" : t.select.loaderVersion);
        lv.setPromptText("recommended");
        TextField modrinth = new TextField(String.join(", ", t.select.modrinth == null ? List.of() : t.select.modrinth));
        modrinth.setPromptText("e.g. essential   (optional)");
        Label preview = Fx.hint("");
        Runnable update = () -> {
            Profile tmp = new Profile();
            Profile.TargetSpec s = new Profile.TargetSpec();
            s.select = new Profile.Selector();
            s.select.minecraft = range.getText();
            s.select.latestPatchOnly = latestPatch.isSelected();
            s.select.limit = limit.getValue();
            for (CheckBox cb : loaders) if (cb.isSelected()) s.select.loaders.add((LoaderType) cb.getUserData());
            s.select.modrinth = projects(modrinth.getText());
            tmp.mods = model.selected.get() == null ? new ArrayList<>() : model.selected.get().mods; // for Modrinth channels
            tmp.targets.add(s);
            preview.setText("Calculating…");
            Fx.async(() -> new TargetExpander(TargetExpander.Catalog.of(model.ctx.meta(),
                            new ing.prometheusreengineer.verity.core.modrinth.ModrinthResolver(model.ctx.modrinth()))).expand(tmp),
                    list -> preview.setText(list.size() + " target(s): " + list.stream().map(Target::id).collect(Collectors.joining(", "))),
                    err -> preview.setText("Cannot expand: " + err.getMessage()));
        };
        range.textProperty().addListener((o, a, b) -> update.run());
        modrinth.textProperty().addListener((o, a, b) -> update.run());
        latestPatch.setOnAction(e -> update.run());
        limit.valueProperty().addListener((o, a, b) -> update.run());
        loaders.forEach(cb -> cb.setOnAction(e -> update.run()));
        update.run();

        GridPane g = Fx.form();
        int r = Fx.row(g, 0, "Minecraft range", range, "Examples: >=1.20 · 1.20.x · >=1.16 <1.21 · [1.8.9,1.12.2] · latest · *");
        r = Fx.row(g, r, "Loaders", loaderBox, "No loader selected = vanilla (or, with Modrinth projects below, every loader they support).");
        r = Fx.row(g, r, "Only on Modrinth for", modrinth,
                "Comma separated Modrinth projects: keep only the version/loader pairs all of them publish files for.");
        r = Fx.row(g, r, "", latestPatch, null);
        r = Fx.row(g, r, "Max versions", limit, "Keep only the N newest per loader (0 = no limit).");
        r = Fx.row(g, r, "Loader version", lv, null);
        preview.setMaxWidth(520);
        g.add(preview, 1, r);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            t.select.minecraft = range.getText().trim().isEmpty() ? "*" : range.getText().trim();
            t.select.loaders.clear();
            for (CheckBox cb : loaders) if (cb.isSelected()) t.select.loaders.add((LoaderType) cb.getUserData());
            t.select.latestPatchOnly = latestPatch.isSelected();
            t.select.limit = limit.getValue();
            t.select.modrinth = projects(modrinth.getText());
            t.select.loaderVersion = lv.getText().isBlank() ? null : lv.getText().trim();
            if (existing == null) model.selected.get().targets.add(t);
            refresh();
            model.changed();
        });
    }

    private static List<String> projects(String text) {
        List<String> out = new ArrayList<>();
        for (String x : text.split("[,\\s]+")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    private void preview() {
        Profile p = model.selected.get();
        if (p == null) return;
        Fx.async(() -> model.orchestrator.targets(p, new RunOptions()), list -> {
            TextArea area = new TextArea(list.stream().map(t -> t.id() + "   " + t.displayName()).collect(Collectors.joining("\n")));
            area.setEditable(false);
            area.setPrefRowCount(Math.min(20, Math.max(4, list.size())));
            Dialog<ButtonType> d = new Dialog<>();
            d.initOwner(stage);
            d.setTitle("Targets");
            d.setHeaderText(list.size() + " client(s) will be launched");
            d.getDialogPane().setContent(area);
            d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            d.show();
        }, err -> Fx.error(stage, "Could not expand targets", err));
    }
}

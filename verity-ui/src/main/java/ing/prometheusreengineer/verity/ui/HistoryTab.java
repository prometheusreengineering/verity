package ing.prometheusreengineer.verity.ui;

import ing.prometheusreengineer.verity.core.profile.Profile;
import ing.prometheusreengineer.verity.core.report.Reports;
import ing.prometheusreengineer.verity.core.run.RunReport;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.application.HostServices;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Past runs (from {@code <home>/runs}) with links to their reports. */
final class HistoryTab {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppModel model;
    private final Stage stage;
    private final HostServices host;
    private final VBox root = new VBox(8);
    private final TableView<RunReport> table = new TableView<>();
    private final CheckBox allProfiles = new CheckBox("All profiles");

    HistoryTab(AppModel model, Stage stage, HostServices host) {
        this.model = model;
        this.stage = stage;
        this.host = host;
        root.setPadding(new Insets(12));
        TableColumn<RunReport, String> when = new TableColumn<>("Started");
        when.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().startedAt == null ? "" : TIME.format(c.getValue().startedAt)));
        TableColumn<RunReport, String> profile = new TableColumn<>("Profile");
        profile.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().profile));
        TableColumn<RunReport, String> result = new TableColumn<>("Result");
        result.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>((c.getValue().prepareOnly ? "prepared " : "") + c.getValue().passed() + "/"
                + c.getValue().results.size() + " passed"));
        TableColumn<RunReport, String> failed = new TableColumn<>("Not passing");
        failed.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(String.join(", ", c.getValue().results.stream()
                .filter(r -> !r.success()).map(r -> r.id + " (" + (r.status == null ? "unknown" : r.status.name().toLowerCase()) + ")").toList())));
        table.getColumns().addAll(List.of(when, profile, result, failed));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No runs yet"));
        table.setRowFactory(t -> {
            TableRow<RunReport> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) open(row.getItem());
            });
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);
        Button open = new Button("Open report");
        open.setOnAction(e -> {
            RunReport r = table.getSelectionModel().getSelectedItem();
            if (r != null) open(r);
        });
        Button folder = new Button("Open folder");
        folder.setOnAction(e -> {
            RunReport r = table.getSelectionModel().getSelectedItem();
            if (r != null) host.showDocument(Path.of(r.runDir).toUri().toString());
        });
        Button delete = new Button("Delete");
        delete.setOnAction(e -> {
            RunReport r = table.getSelectionModel().getSelectedItem();
            if (r == null || !Fx.confirm(stage, "Delete this run's reports and logs?", r.runDir)) return;
            try {
                FileUtil.deleteRecursively(Path.of(r.runDir));
                refresh();
            } catch (Exception ex) {
                Fx.error(stage, "Could not delete", ex);
            }
        });
        Button reload = new Button("Refresh");
        reload.setOnAction(e -> refresh());
        allProfiles.setOnAction(e -> refresh());
        root.getChildren().addAll(new HBox(8, open, folder, delete, Fx.spacer(), allProfiles, reload), table);
        model.selected.addListener((o, a, b) -> refresh());
    }

    Parent root() {
        return root;
    }

    private void open(RunReport r) {
        host.showDocument(Path.of(r.runDir).resolve(Reports.HTML).toUri().toString());
    }

    void refresh() {
        Profile p = model.selected.get();
        boolean all = allProfiles.isSelected();
        Fx.async(() -> {
            List<RunReport> out = new ArrayList<>();
            if (!Files.isDirectory(model.home.runs())) return out;
            try (var dirs = Files.list(model.home.runs())) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    if (!Files.isRegularFile(d.resolve(Reports.JSON))) continue;
                    try {
                        RunReport r = Reports.read(d);
                        r.runDir = d.toString();
                        if (all || p == null || p.name.equals(r.profile)) out.add(r);
                    } catch (Exception ignored) {
                        // unreadable report
                    }
                }
            }
            out.sort(Comparator.comparing((RunReport r) -> r.startedAt == null ? java.time.Instant.EPOCH : r.startedAt).reversed());
            return out;
        }, list -> table.setItems(FXCollections.observableArrayList(list)), err -> Fx.error(stage, "Could not read run history", err));
    }
}

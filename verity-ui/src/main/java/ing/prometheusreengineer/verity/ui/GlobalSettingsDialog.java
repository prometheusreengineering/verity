package ing.prometheusreengineer.verity.ui;

import ing.prometheusreengineer.verity.core.home.GlobalSettings;
import ing.prometheusreengineer.verity.core.util.FileUtil;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/** Machine-wide preferences ({@code settings.json}) and cache maintenance. */
final class GlobalSettingsDialog {
    private final AppModel model;
    private final Stage stage;

    GlobalSettingsDialog(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
    }

    void show() {
        GlobalSettings s = model.ctx.settings();
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Preferences");
        d.setHeaderText("Applies to all profiles on this machine");
        GridPane g = Fx.form();
        Spinner<Integer> parallel = new Spinner<>(0, 64, s.maxParallel);
        parallel.setEditable(true);
        Spinner<Integer> downloads = new Spinner<>(1, 64, s.downloadThreads);
        downloads.setEditable(true);
        Spinner<Integer> ttl = new Spinner<>(0, 10080, s.metadataTtlMinutes, 30);
        ttl.setEditable(true);
        StringBuilder jo = new StringBuilder();
        s.javaOverrides.forEach((k, v) -> jo.append(k).append('=').append(v).append('\n'));
        TextArea java = new TextArea(jo.toString().trim());
        java.setPrefRowCount(3);
        java.setPromptText("8=C:\\\\Java\\\\jdk8\n17=/usr/lib/jvm/java-17");
        TextField client = new TextField(s.msaClientId);
        Label sizes = Fx.hint("Calculating…");
        Fx.async(() -> {
            long cache = FileUtil.sizeOf(model.home.libraries()) + FileUtil.sizeOf(model.home.assets()) + FileUtil.sizeOf(model.home.versions())
                    + FileUtil.sizeOf(model.home.runtimes()) + FileUtil.sizeOf(model.home.installers());
            long inst = FileUtil.sizeOf(model.home.instances());
            long runs = FileUtil.sizeOf(model.home.runs());
            return String.format("Game files %.0f MiB · instances %.0f MiB · run reports %.0f MiB", cache / 1048576.0, inst / 1048576.0,
                    runs / 1048576.0);
        }, sizes::setText, err -> sizes.setText(err.getMessage()));
        Button cleanInstances = new Button("Delete instances");
        cleanInstances.setOnAction(e -> clean(model.home.instances(), sizes));
        Button cleanRuns = new Button("Delete run reports");
        cleanRuns.setOnAction(e -> clean(model.home.runs(), sizes));

        int r = 0;
        r = Fx.row(g, r, "Data directory", new Label(model.home.root().toString()), "Change with --home or the VERITY_HOME environment variable.");
        r = Fx.row(g, r, "Max parallel clients", parallel, "Default for profiles that use automatic (0 = based on CPU cores and memory).");
        r = Fx.row(g, r, "Parallel downloads", downloads, null);
        r = Fx.row(g, r, "Version list cache (min)", ttl, "How long version lists are reused before refreshing.");
        r = Fx.row(g, r, "Java overrides", java, "major=path per line, used instead of Mojang's runtime for that Java version (path to java or a Java home).");
        r = Fx.row(g, r, "Microsoft client id", client, "Advanced: OAuth client id for sign-in.");
        r = Fx.row(g, r, "Disk usage", sizes, null);
        Fx.row(g, r, "", new HBox(8, cleanInstances, cleanRuns), null);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            s.maxParallel = parallel.getValue();
            s.downloadThreads = downloads.getValue();
            s.metadataTtlMinutes = ttl.getValue();
            Map<Integer, String> overrides = new LinkedHashMap<>();
            for (String line : java.getText().split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                try {
                    overrides.put(Integer.parseInt(line.substring(0, eq).trim()), line.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                    // skip malformed line
                }
            }
            s.javaOverrides = overrides;
            s.msaClientId = client.getText().trim();
            try {
                s.save(model.home);
                Fx.info(stage, "Saved", "Download and cache settings take effect after restarting Verity.");
            } catch (Exception e) {
                Fx.error(stage, "Could not save settings", e);
            }
        });
    }

    private void clean(Path dir, Label sizes) {
        if (!Fx.confirm(stage, "Delete " + dir + "?", "This cannot be undone.")) return;
        Fx.async(() -> {
            FileUtil.deleteRecursively(dir);
            return null;
        }, x -> sizes.setText("Deleted " + dir), err -> Fx.error(stage, "Could not delete", err));
    }
}

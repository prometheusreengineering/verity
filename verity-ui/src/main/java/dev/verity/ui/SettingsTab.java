package dev.verity.ui;

import dev.verity.core.auth.StoredAccount;
import dev.verity.core.profile.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/** Everything else about how the profile's clients run. Edits save automatically. */
final class SettingsTab {
    private final AppModel model;
    private final Stage stage;
    private final ScrollPane root = new ScrollPane();

    SettingsTab(AppModel model, Stage stage) {
        this.model = model;
        this.stage = stage;
        root.setFitToWidth(true);
        model.selected.addListener((o, a, b) -> build(b));
        build(model.selected.get());
    }

    Parent root() {
        return root;
    }

    private void build(Profile p) {
        if (p == null) {
            root.setContent(null);
            return;
        }
        GridPane g = Fx.form();
        int r = 0;

        r = section(g, r, "General");
        r = Fx.row(g, r, "Description", text(p.description, v -> p.description = v.isBlank() ? null : v), null);

        r = section(g, r, "Game");
        r = Fx.row(g, r, "Memory (MiB)", spinner(512, 32768, p.jvm.memoryMb, 256, v -> p.jvm.memoryMb = v), "Max heap (-Xmx) for each client.");
        r = Fx.row(g, r, "JVM arguments", lines(p.jvm.args, v -> p.jvm.args = v), "One per line, added to every client's java command.");
        r = Fx.row(g, r, "Game arguments", lines(p.game.args, v -> p.game.args = v), "One per line, appended after Minecraft's own arguments.");
        r = Fx.row(g, r, "Environment", env(p.game.env, v -> p.game.env = v), "KEY=VALUE per line.");
        r = Fx.row(g, r, "Extra files", files(p), "Copied into each game directory before launch: one 'source -> target' per line, "
                + "e.g. 'configs/mymod.json -> config/mymod.json'. Folders work too.");

        r = section(g, r, "Display");
        ComboBox<Profile.DisplayMode> mode = Fx.enumCombo(Profile.DisplayMode.values(), Profile.DisplayMode::id);
        mode.setValue(p.display.mode);
        mode.setOnAction(e -> {
            p.display.mode = mode.getValue();
            model.changed();
        });
        r = Fx.row(g, r, "Mode", mode, "headless: rendered for real but invisible (Xvfb on Linux, hidden desktop on Windows). window: normal windows.");
        HBox size = new HBox(8, spinner(320, 3840, p.display.width, 2, v -> p.display.width = v), new Label("×"),
                spinner(240, 2160, p.display.height, 2, v -> p.display.height = v));
        r = Fx.row(g, r, "Window size", size, null);
        ComboBox<Profile.SoftwareGl> gl = Fx.enumCombo(Profile.SoftwareGl.values(), Profile.SoftwareGl::id);
        gl.setValue(p.display.softwareGl);
        gl.setOnAction(e -> {
            p.display.softwareGl = gl.getValue();
            model.changed();
        });
        r = Fx.row(g, r, "Software OpenGL", gl, "Mesa llvmpipe. auto: used for headless runs (needed without a GPU, e.g. CI). on: always. off: use the GPU driver.");

        r = section(g, r, "Account");
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("offline", "msa"));
        type.setValue(p.account.type == null ? "offline" : p.account.type);
        TextField user = text(p.account.username, v -> p.account.username = v);
        ComboBox<String> msa = new ComboBox<>();
        try {
            for (StoredAccount a : model.orchestrator.accounts().store().list()) msa.getItems().add(a.name + " (" + a.id + ")");
        } catch (Exception ignored) {
            // no accounts
        }
        msa.setPromptText(msa.getItems().isEmpty() ? "No accounts — use Accounts in the toolbar" : "Pick an account");
        if (p.account.id != null) msa.getItems().stream().filter(s -> s.contains(p.account.id)).findFirst().ifPresent(msa::setValue);
        msa.setOnAction(e -> {
            String v = msa.getValue();
            p.account.id = v == null ? null : v.substring(v.lastIndexOf('(') + 1, v.length() - 1);
            model.changed();
        });
        Runnable sync = () -> {
            boolean offline = "offline".equals(type.getValue());
            user.setDisable(!offline);
            msa.setDisable(offline);
        };
        type.setOnAction(e -> {
            p.account.type = type.getValue();
            sync.run();
            model.changed();
        });
        sync.run();
        r = Fx.row(g, r, "Type", type, "offline: no login, any name. msa: a signed-in Microsoft account (needed when a mod talks to online services).");
        r = Fx.row(g, r, "Offline name", user, null);
        r = Fx.row(g, r, "Microsoft account", msa, null);

        r = section(g, r, "Test");
        r = Fx.row(g, r, "Connect timeout (s)", spinner(10, 7200, p.test.startupTimeoutSec, 30, v -> p.test.startupTimeoutSec = v),
                "Fail if the mod has not connected through verity-api by then (first runs include downloads and are slow).");
        r = Fx.row(g, r, "Total timeout (s)", spinner(10, 14400, p.test.timeoutSec, 60, v -> p.test.timeoutSec = v),
                "Fail if Verity.finish() was not called by then.");
        r = Fx.row(g, r, "Shutdown grace (s)", spinner(0, 120, p.test.shutdownGraceSec, 1, v -> p.test.shutdownGraceSec = v), null);
        r = Fx.row(g, r, "Vanilla smoke (s)", spinner(0, 3600, p.test.vanillaSmokeSec, 10, v -> p.test.vanillaSmokeSec = v),
                "Vanilla cannot load mods: a vanilla client passes after running this long without crashing. 0 = require verity-api.");

        r = section(g, r, "Run");
        r = Fx.row(g, r, "Max parallel", spinner(0, 64, p.run.maxParallel, 1, v -> p.run.maxParallel = v), "0 = automatic (CPU cores and memory).");
        r = Fx.row(g, r, "", check("Keep instances between runs (worlds, configs)", p.run.keepInstances, v -> p.run.keepInstances = v), null);
        Fx.row(g, r, "", check("Sandbox the user home (mods cannot touch your real profile / .minecraft)", p.run.isolateHome,
                v -> p.run.isolateHome = v), null);
        root.setContent(g);
    }

    private static int section(GridPane g, int r, String title) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        g.add(l, 0, r, 2, 1);
        return r + 1;
    }

    private TextField text(String value, Consumer<String> set) {
        TextField f = new TextField(value == null ? "" : value);
        f.textProperty().addListener((o, a, b) -> {
            set.accept(b);
            model.changed();
        });
        return f;
    }

    private Spinner<Integer> spinner(int min, int max, int value, int step, Consumer<Integer> set) {
        Spinner<Integer> s = new Spinner<>(min, max, Math.max(min, Math.min(max, value)), step);
        s.setEditable(true);
        s.setPrefWidth(130);
        s.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                set.accept(b);
                model.changed();
            }
        });
        return s;
    }

    private CheckBox check(String label, boolean value, Consumer<Boolean> set) {
        CheckBox c = new CheckBox(label);
        c.setSelected(value);
        c.setOnAction(e -> {
            set.accept(c.isSelected());
            model.changed();
        });
        return c;
    }

    private TextArea lines(List<String> value, Consumer<List<String>> set) {
        TextArea a = new TextArea(String.join("\n", value));
        a.setPrefRowCount(3);
        a.textProperty().addListener((o, x, b) -> {
            List<String> out = new ArrayList<>(Arrays.stream(b.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            set.accept(out);
            model.changed();
        });
        return a;
    }

    private TextArea env(Map<String, String> value, Consumer<Map<String, String>> set) {
        StringBuilder b = new StringBuilder();
        value.forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        TextArea a = new TextArea(b.toString().trim());
        a.setPrefRowCount(2);
        a.textProperty().addListener((o, x, t) -> {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : t.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            set.accept(out);
            model.changed();
        });
        return a;
    }

    private TextArea files(Profile p) {
        StringBuilder b = new StringBuilder();
        for (Profile.FileEntry f : p.files) b.append(f.from).append(f.to != null ? " -> " + f.to : "").append('\n');
        TextArea a = new TextArea(b.toString().trim());
        a.setPrefRowCount(2);
        a.textProperty().addListener((o, x, t) -> {
            List<Profile.FileEntry> out = new ArrayList<>();
            for (String line : t.split("\n")) {
                if (line.isBlank()) continue;
                Profile.FileEntry f = new Profile.FileEntry();
                String[] parts = line.split("->", 2);
                f.from = parts[0].trim();
                f.to = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
                out.add(f);
            }
            p.files = out;
            model.changed();
        });
        return a;
    }
}

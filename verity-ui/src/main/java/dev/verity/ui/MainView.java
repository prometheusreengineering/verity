package dev.verity.ui;

import dev.verity.core.profile.Profile;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Window layout: profiles on the left, the selected profile's tabs in the middle. */
final class MainView {
    private final AppModel model;
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final Label status = new Label();
    private final RunTab runTab;
    private final TabPane tabs = new TabPane();

    MainView(AppModel model, Stage stage, HostServices host) {
        this.model = model;
        this.stage = stage;
        ProfileListPane profiles = new ProfileListPane(model, stage);
        TargetsTab targets = new TargetsTab(model, stage);
        ModsTab mods = new ModsTab(model, stage);
        SettingsTab settings = new SettingsTab(model, stage);
        runTab = new RunTab(model, stage, host, this::setStatus);
        HistoryTab history = new HistoryTab(model, stage, host);

        tabs.getTabs().addAll(
                tab("Targets", targets.root()),
                tab("Mods", mods.root()),
                tab("Settings", settings.root()),
                tab("Run", runTab.root()),
                tab("History", history.root()));
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.getText().equals("History")) history.refresh();
        });

        Label empty = new Label("Create or select a profile on the left.\nA profile holds the versions, loaders, mods and settings for one test run.");
        empty.getStyleClass().add("empty-state");
        empty.setWrapText(true);
        StackPane center = new StackPane(tabs, empty);
        empty.visibleProperty().bind(model.selected.isNull());
        tabs.visibleProperty().bind(model.selected.isNotNull());

        Button accounts = new Button("Accounts");
        accounts.setOnAction(e -> new AccountsDialog(model, stage, host).show());
        Button settingsBtn = new Button("Preferences");
        settingsBtn.setOnAction(e -> new GlobalSettingsDialog(model, stage).show());
        Button theme = new Button("Theme");
        theme.setOnAction(e -> VerityApp.applyTheme(!VerityApp.isDark()));
        Label title = new Label("Verity");
        title.getStyleClass().add("title-3");
        Label profileName = new Label();
        model.selected.addListener((o, a, b) -> profileName.setText(b == null ? "" : "·  " + b.name));
        model.edits.addListener((o, a, b) -> {
            Profile p = model.selected.get();
            profileName.setText(p == null ? "" : "·  " + p.name);
        });
        HBox top = new HBox(title, profileName, Fx.spacer(), runTab.quickRunButton(), accounts, settingsBtn, theme);
        top.getStyleClass().add("toolbar-row");
        top.setAlignment(Pos.CENTER_LEFT);

        HBox bottom = new HBox(status, Fx.spacer(), new Label("Data: " + model.home.root()));
        bottom.getStyleClass().add("status-bar");

        root.setTop(top);
        root.setLeft(profiles.root());
        root.setCenter(center);
        root.setBottom(bottom);

        model.onSaveError(() -> setStatus("Could not save profile: " + model.lastSaveError().getMessage()));
        setStatus(model.profiles.isEmpty() ? "Welcome! Start by creating a profile." : model.profiles.size() + " profile(s)");
    }

    void showRunTab() {
        tabs.getSelectionModel().select(3);
    }

    private static Tab tab(String name, Parent content) {
        Tab t = new Tab(name, content);
        t.setClosable(false);
        return t;
    }

    void setStatus(String text) {
        status.setText(text);
    }

    Parent root() {
        return root;
    }

    boolean confirmClose() {
        model.saveNow();
        if (runTab.isRunning()) {
            if (!Fx.confirm(stage, "A run is in progress", "Closing Verity stops all running clients. Close anyway?")) return false;
            runTab.stop();
        }
        return true;
    }

    void shutdown() {
        runTab.stop();
    }
}

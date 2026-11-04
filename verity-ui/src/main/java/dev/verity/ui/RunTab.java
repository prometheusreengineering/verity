package dev.verity.ui;

import dev.verity.core.profile.Profile;
import dev.verity.core.profile.ProfileStore;
import dev.verity.core.report.Reports;
import dev.verity.core.run.CheckResult;
import dev.verity.core.run.RunHandle;
import dev.verity.core.run.RunListener;
import dev.verity.core.run.RunOptions;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.Target;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Starts runs and shows every client live: a card per target, and the selected target's log and checks. */
final class RunTab {
    private static final int MAX_LOG_LINES = 20_000;

    private final AppModel model;
    private final Stage stage;
    private final HostServices host;
    private final Consumer<String> status;
    private final VBox root = new VBox();
    private final FlowPane cards = new FlowPane(10, 10);
    private final Map<String, Card> cardById = new LinkedHashMap<>();
    private final ListView<String> log = new ListView<>();
    private final ListView<CheckResult> checks = new ListView<>();
    private final Label logTitle = new Label("Select a target to see its log");
    private final Label summary = new Label();
    private final Button runButton = new Button("Run all");
    private final Button stopButton = new Button("Stop");
    private final Button reportButton = new Button("Open report");
    private final Button quickRun = new Button("▶ Run");
    private final ComboBox<String> display = new ComboBox<>(FXCollections.observableArrayList("profile setting", "headless", "window"));
    private final TextField only = new TextField();
    private final CheckBox follow = new CheckBox("Follow");
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private volatile RunHandle handle;
    private RunReport lastReport;
    private String selectedId;

    RunTab(AppModel model, Stage stage, HostServices host, Consumer<String> status) {
        this.model = model;
        this.stage = stage;
        this.host = host;
        this.status = status;

        runButton.getStyleClass().add("success");
        runButton.setOnAction(e -> start());
        quickRun.getStyleClass().add("success");
        quickRun.setOnAction(e -> start());
        quickRun.disableProperty().bind(model.selected.isNull());
        stopButton.getStyleClass().add("danger");
        stopButton.setOnAction(e -> stop());
        stopButton.setDisable(true);
        reportButton.setDisable(true);
        reportButton.setOnAction(e -> {
            if (lastReport != null) host.showDocument(Path.of(lastReport.runDir).resolve(Reports.HTML).toUri().toString());
        });
        display.setValue("profile setting");
        only.setPromptText("Filter targets, e.g. 1.20.1 fabric");
        only.setPrefColumnCount(22);
        follow.setSelected(true);

        HBox controls = new HBox(8, runButton, stopButton, new Label("Display:"), display, only, Fx.spacer(), summary, reportButton);
        controls.getStyleClass().add("toolbar-row");

        ScrollPane cardScroll = new ScrollPane(cards);
        cardScroll.setFitToWidth(true);
        cards.setPadding(new Insets(10));

        log.getStyleClass().add("log-view");
        log.setPlaceholder(new Label("No output yet"));
        checks.setPrefHeight(140);
        checks.setPlaceholder(new Label("No checks reported yet"));
        checks.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(CheckResult c, boolean empty) {
                super.updateItem(c, empty);
                getStyleClass().removeAll("check-pass", "check-fail");
                if (empty || c == null) {
                    setText(null);
                    return;
                }
                setText((c.passed() ? "✔ " : "✘ ") + c.name() + (c.message() != null ? " — " + c.message() : ""));
                getStyleClass().add(c.passed() ? "check-pass" : "check-fail");
            }
        });
        VBox.setVgrow(log, Priority.ALWAYS);
        logTitle.getStyleClass().add("section-title");
        VBox logPane = new VBox(6, new HBox(8, logTitle, Fx.spacer(), follow), checks, log);
        logPane.setPadding(new Insets(10));

        SplitPane split = new SplitPane(cardScroll, logPane);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);
        root.getChildren().addAll(controls, split);

        // Batch UI updates from worker threads to keep the FX thread responsive with many chatty clients.
        Timeline pump = new Timeline(new KeyFrame(Duration.millis(120), e -> {
            Runnable r;
            int n = 0;
            while ((r = pending.poll()) != null && n++ < 5000) r.run();
        }));
        pump.setCycleCount(Timeline.INDEFINITE);
        pump.play();
    }

    Parent root() {
        return root;
    }

    Button quickRunButton() {
        return quickRun;
    }

    boolean isRunning() {
        return handle != null && !handle.report().isDone();
    }

    void stop() {
        RunHandle h = handle;
        if (h != null) h.cancel();
    }

    private void start() {
        Profile p = model.selected.get();
        if (p == null || isRunning()) return;
        model.saveNow();
        Profile snapshot = ProfileStore.copy(p);
        RunOptions o = new RunOptions();
        String d = display.getValue();
        if ("headless".equals(d)) o.display = Profile.DisplayMode.HEADLESS;
        if ("window".equals(d)) o.display = Profile.DisplayMode.WINDOW;
        for (String s : only.getText().split("[,\\s]+")) if (!s.isBlank()) o.only.add(s.trim());

        cards.getChildren().clear();
        cardById.clear();
        log.getItems().clear();
        checks.getItems().clear();
        selectedId = null;
        reportButton.setDisable(true);
        summary.setText("Starting…");
        setRunning(true);
        if (root.getScene() != null) {
            var tabs = root.getParent();
            while (tabs != null && !(tabs instanceof javafx.scene.control.TabPane)) tabs = tabs.getParent();
            if (tabs instanceof javafx.scene.control.TabPane tp) tp.getSelectionModel().select(3);
        }

        Fx.async(() -> model.orchestrator.start(snapshot, o, new UiListener()), h -> {
            handle = h;
            h.report().whenComplete((r, err) -> Platform.runLater(() -> finished(r, err)));
        }, err -> {
            setRunning(false);
            summary.setText("");
            Fx.error(stage, "Cannot start run", err);
        });
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        quickRun.disableProperty().unbind();
        quickRun.setDisable(running);
        if (!running) quickRun.disableProperty().bind(model.selected.isNull());
        stopButton.setDisable(!running);
    }

    private void finished(RunReport r, Throwable err) {
        setRunning(false);
        if (err != null) {
            Fx.error(stage, "Run failed", err);
            return;
        }
        lastReport = r;
        reportButton.setDisable(false);
        long skipped = r.count(Status.SKIPPED);
        String text = r.passed() + "/" + (r.results.size() - skipped) + " passed" + (skipped > 0 ? " (" + skipped + " skipped)" : "");
        summary.setText(text);
        status.accept("Run finished: " + text + " · " + r.runDir);
    }

    private void select(String id) {
        selectedId = id;
        cardById.forEach((k, c) -> c.box.getStyleClass().remove("selected"));
        Card c = cardById.get(id);
        if (c == null) return;
        c.box.getStyleClass().add("selected");
        logTitle.setText(id);
        log.setItems(c.lines);
        checks.setItems(c.checks);
        if (!c.lines.isEmpty()) log.scrollTo(c.lines.size() - 1);
    }

    /** One target tile. */
    private final class Card {
        final VBox box = new VBox();
        final Label state = new Label("PENDING");
        final Label stage = new Label();
        final ProgressBar progress = new ProgressBar(0);
        final Label checkSummary = new Label();
        final ObservableList<String> lines = FXCollections.observableArrayList();
        final ObservableList<CheckResult> checks = FXCollections.observableArrayList();
        Status status = Status.PENDING;

        Card(Target t) {
            Label title = new Label(t.id());
            title.getStyleClass().add("card-title");
            Label sub = new Label(t.displayName());
            sub.getStyleClass().add("card-stage");
            stage.getStyleClass().add("card-stage");
            stage.setWrapText(true);
            stage.setMaxWidth(210);
            progress.setMaxWidth(Double.MAX_VALUE);
            progress.setVisible(false);
            state.getStyleClass().addAll("status-label", "status-PENDING");
            box.getChildren().addAll(new HBox(8, title, Fx.spacer(), state), sub, stage, progress, checkSummary);
            box.getStyleClass().add("target-card");
            box.setOnMouseClicked(e -> select(t.id()));
        }

        void setStatus(Status s, String message) {
            box.getStyleClass().removeIf(c -> c.startsWith("state-"));
            box.getStyleClass().add("state-" + s.name());
            state.getStyleClass().removeIf(c -> c.startsWith("status-") && !c.equals("status-label"));
            state.getStyleClass().add("status-" + s.name());
            state.setText(s.name().replace('_', ' '));
            status = s;
            if (message != null) stage.setText(message);
            if (s.terminal()) progress.setVisible(false);
        }

        void setProgress(String text, long done, long total) {
            if (text != null) stage.setText(text);
            if (total > 0) {
                progress.setVisible(true);
                progress.setProgress((double) done / total);
            }
        }

        void addLine(String line) {
            lines.add(line);
            if (lines.size() > MAX_LOG_LINES) lines.remove(0, lines.size() - MAX_LOG_LINES);
            if (follow.isSelected() && selectedId != null && cardById.get(selectedId) == this) log.scrollTo(lines.size() - 1);
        }

        void addCheck(CheckResult c) {
            checks.add(c);
            long failed = checks.stream().filter(x -> !x.passed()).count();
            checkSummary.setText(checks.size() + " checks" + (failed > 0 ? ", " + failed + " failed" : ""));
            checkSummary.getStyleClass().setAll("label", failed > 0 ? "check-fail" : "check-pass");
        }
    }

    private final class UiListener implements RunListener {
        @Override
        public void runStarted(String profile, List<Target> targets, Path runDir) {
            List<Target> copy = new ArrayList<>(targets);
            Platform.runLater(() -> {
                for (Target t : copy) {
                    Card c = new Card(t);
                    cardById.put(t.id(), c);
                    cards.getChildren().add(c.box);
                }
                if (!copy.isEmpty()) select(copy.get(0).id());
                summary.setText(copy.size() + " target(s) running");
                status.accept("Running " + profile + " · reports in " + runDir);
            });
        }

        @Override
        public void warning(String message) {
            Platform.runLater(() -> status.accept("Warning: " + message));
        }

        @Override
        public void targetStatus(Target target, Status s, String message) {
            pending.add(() -> {
                Card c = cardById.get(target.id());
                if (c != null) c.setStatus(s, message);
            });
        }

        @Override
        public void targetProgress(Target target, String stage, long done, long total) {
            pending.add(() -> {
                Card c = cardById.get(target.id());
                if (c != null) c.setProgress(stage, done, total);
            });
        }

        @Override
        public void targetLog(Target target, String line) {
            pending.add(() -> {
                Card c = cardById.get(target.id());
                if (c != null) c.addLine(line);
            });
        }

        @Override
        public void targetCheck(Target target, CheckResult check) {
            pending.add(() -> {
                Card c = cardById.get(target.id());
                if (c != null) c.addCheck(check);
            });
        }
    }
}

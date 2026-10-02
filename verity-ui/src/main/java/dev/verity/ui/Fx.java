package dev.verity.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/** Small JavaFX helpers. */
final class Fx {
    static final ExecutorService BACKGROUND = Executors.newVirtualThreadPerTaskExecutor();

    private Fx() {
    }

    /** Runs {@code work} off the FX thread and hands the result (or error) back on it. */
    static <T> void async(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        BACKGROUND.submit(() -> {
            try {
                T r = work.call();
                Platform.runLater(() -> onSuccess.accept(r));
            } catch (Throwable t) {
                Platform.runLater(() -> onError.accept(t));
            }
        });
    }

    static void error(Window owner, String header, Throwable t) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Verity");
        a.setHeaderText(header);
        a.setContentText(t.getMessage() != null ? t.getMessage() : t.toString());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextArea details = new TextArea(sw.toString());
        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefRowCount(12);
        a.getDialogPane().setExpandableContent(details);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.show();
    }

    static void info(Window owner, String header, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Verity");
        a.setHeaderText(header);
        a.setContentText(text);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.show();
    }

    static boolean confirm(Window owner, String header, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Verity");
        a.setHeaderText(header);
        a.setContentText(text);
        return a.showAndWait().filter(b -> b.getButtonData().isDefaultButton()).isPresent();
    }

    static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        return g;
    }

    /** Adds a labeled row; returns the next row index. */
    static int row(GridPane g, int row, String label, Node field, String help) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        g.add(l, 0, row);
        g.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        if (help != null) {
            Tooltip tip = new Tooltip(help);
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            Tooltip.install(l, tip);
            if (field instanceof javafx.scene.control.Control c) c.setTooltip(tip);
        }
        return row + 1;
    }

    static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    /** A combo box showing enum constants by a friendly name. */
    static <E> javafx.scene.control.ComboBox<E> enumCombo(E[] values, java.util.function.Function<E, String> name) {
        javafx.scene.control.ComboBox<E> box = new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList(values));
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(E e) {
                return e == null ? "" : name.apply(e);
            }

            @Override
            public E fromString(String s) {
                for (E e : values) if (name.apply(e).equals(s)) return e;
                return null;
            }
        });
        return box;
    }

    static Region spacer() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}

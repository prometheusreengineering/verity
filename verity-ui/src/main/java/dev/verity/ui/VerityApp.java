package dev.verity.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class VerityApp extends Application {
    private static boolean dark = true;
    private MainView main;

    static void applyTheme(boolean darkTheme) {
        dark = darkTheme;
        Application.setUserAgentStylesheet(darkTheme ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
    }

    static boolean isDark() {
        return dark;
    }

    @Override
    public void start(Stage stage) {
        applyTheme(!"light".equalsIgnoreCase(System.getProperty("verity.theme", "")));
        AppModel model;
        try {
            model = new AppModel(VerityUi.homeOverride);
        } catch (Exception e) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Could not open the Verity data directory: " + e.getMessage());
            a.showAndWait();
            Platform.exit();
            return;
        }
        main = new MainView(model, stage, getHostServices());
        Scene scene = new Scene(main.root(), 1280, 800);
        scene.getStylesheets().add(VerityApp.class.getResource("verity.css").toExternalForm());
        stage.setTitle("Verity");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(e -> {
            if (!main.confirmClose()) e.consume();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (main != null) main.shutdown();
        Platform.exit();
        System.exit(0);
    }
}

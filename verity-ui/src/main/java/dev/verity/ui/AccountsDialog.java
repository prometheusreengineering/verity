package dev.verity.ui;

import dev.verity.core.auth.AccountService;
import dev.verity.core.auth.MicrosoftAuth;
import dev.verity.core.auth.StoredAccount;
import javafx.application.HostServices;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Microsoft accounts: list, sign in with a device code, remove. */
final class AccountsDialog {
    private final AppModel model;
    private final Stage stage;
    private final HostServices host;

    AccountsDialog(AppModel model, Stage stage, HostServices host) {
        this.model = model;
        this.stage = stage;
        this.host = host;
    }

    void show() {
        AccountService service = model.orchestrator.accounts();
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Accounts");
        d.setHeaderText("Microsoft accounts for profiles with account type \"msa\". Offline profiles need none.");
        ListView<StoredAccount> list = new ListView<>();
        list.setPrefHeight(160);
        list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(StoredAccount a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : a.name + "   " + a.id);
            }
        });
        Runnable reload = () -> {
            try {
                list.setItems(FXCollections.observableArrayList(service.store().list()));
            } catch (Exception e) {
                Fx.error(stage, "Could not read accounts", e);
            }
        };
        reload.run();

        Label code = new Label();
        code.getStyleClass().add("device-code");
        Hyperlink link = new Hyperlink();
        Label state = Fx.hint("");
        VBox signIn = new VBox(6, state, code, link);
        signIn.setVisible(false);

        Button login = new Button("Sign in with Microsoft…");
        login.getStyleClass().add("accent");
        login.setOnAction(e -> {
            login.setDisable(true);
            signIn.setVisible(true);
            state.setText("Requesting a code…");
            MicrosoftAuth auth = service.microsoft();
            Fx.async(auth::requestDeviceCode, dc -> {
                code.setText(dc.userCode());
                link.setText(dc.verificationUri());
                link.setOnAction(x -> {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(dc.userCode());
                    Clipboard.getSystemClipboard().setContent(c);
                    host.showDocument(dc.verificationUri());
                });
                state.setText("Open the link, enter this code (copied when you click the link), then approve:");
                Fx.async(() -> auth.awaitDeviceCode(dc), acc -> {
                    try {
                        service.store().put(acc);
                    } catch (Exception ex) {
                        Fx.error(stage, "Could not save account", ex);
                    }
                    state.setText("Signed in as " + acc.name);
                    code.setText("");
                    link.setText("");
                    login.setDisable(false);
                    reload.run();
                }, err -> {
                    state.setText("Sign-in failed: " + err.getMessage());
                    login.setDisable(false);
                });
            }, err -> {
                state.setText("Could not start sign-in: " + err.getMessage());
                login.setDisable(false);
            });
        });
        Button remove = new Button("Remove");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            StoredAccount a = list.getSelectionModel().getSelectedItem();
            try {
                service.store().remove(a.id);
                reload.run();
            } catch (Exception ex) {
                Fx.error(stage, "Could not remove", ex);
            }
        });
        VBox content = new VBox(10, list, new HBox(8, login, remove), signIn);
        content.setPrefWidth(520);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.show();
    }
}

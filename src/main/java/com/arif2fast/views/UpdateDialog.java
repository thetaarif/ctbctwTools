package com.arif2fast.views;

import com.arif2fast.models.UpdateInfo;
import com.arif2fast.services.UpdateService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * Dialog for displaying update information and handling download.
 */
@Slf4j
public class UpdateDialog {

    private final Stage dialog;
    private final UpdateService updateService;
    private final UpdateInfo updateInfo;
    private final String currentVersion;

    private ProgressBar progressBar;
    private Label statusLabel;
    private Button downloadButton;
    private Button skipButton;

    public UpdateDialog(Stage owner, UpdateService updateService, UpdateInfo updateInfo, String currentVersion) {
        this.updateService = updateService;
        this.updateInfo = updateInfo;
        this.currentVersion = currentVersion;

        dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.initStyle(StageStyle.UTILITY);
        dialog.setTitle("Update Available");
        dialog.setResizable(false);

        VBox content = createContent();
        Scene scene = new Scene(content);
        dialog.setScene(scene);
    }

    private VBox createContent() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        vbox.setAlignment(Pos.CENTER);
        vbox.setStyle("-fx-background-color: #f5f5f5;");

        // Title
        Label titleLabel = new Label("🎉 New Version Available!");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        titleLabel.setStyle("-fx-text-fill: #2e7d32;");

        // Version info
        Label versionLabel = new Label(
                "Current: v" + currentVersion + "  →  New: v" + updateInfo.getVersion());
        versionLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        versionLabel.setStyle("-fx-text-fill: #424242;");

        // Release notes (if available)
        VBox notesBox = new VBox(5);
        if (updateInfo.getReleaseNotes() != null && !updateInfo.getReleaseNotes().isBlank()) {
            Label notesTitle = new Label("What's New:");
            notesTitle.setFont(Font.font("System", FontWeight.BOLD, 12));

            TextArea notesArea = new TextArea(updateInfo.getReleaseNotes());
            notesArea.setEditable(false);
            notesArea.setWrapText(true);
            notesArea.setPrefRowCount(3);
            notesArea.setMaxWidth(350);
            notesArea.setStyle("-fx-control-inner-background: #ffffff;");

            notesBox.getChildren().addAll(notesTitle, notesArea);
        }

        // Progress section
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.setVisible(false);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #616161;");

        // Buttons
        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);

        downloadButton = new Button("Download Update");
        downloadButton.setStyle("-fx-background-color: #4caf50; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-padding: 8 20;");
        downloadButton.setOnAction(e -> startDownload());

        skipButton = new Button("Skip for Now");
        skipButton.setStyle("-fx-background-color: #9e9e9e; -fx-text-fill: white; -fx-padding: 8 20;");
        skipButton.setOnAction(e -> dialog.close());

        buttonBox.getChildren().addAll(downloadButton, skipButton);

        vbox.getChildren().addAll(titleLabel, versionLabel);
        if (!notesBox.getChildren().isEmpty()) {
            vbox.getChildren().add(notesBox);
        }
        vbox.getChildren().addAll(progressBar, statusLabel, buttonBox);

        return vbox;
    }

    private void startDownload() {
        downloadButton.setDisable(true);
        skipButton.setDisable(true);
        progressBar.setVisible(true);
        statusLabel.setText("Downloading...");

        updateService.downloadUpdateAsync(updateInfo, progress -> {
            Platform.runLater(() -> {
                progressBar.setProgress(progress);
                statusLabel.setText(String.format("Downloading... %.0f%%", progress * 100));
            });
        }).thenAccept(result -> {
            Platform.runLater(() -> {
                if (result.isPresent()) {
                    Path downloadedFile = result.get();
                    showDownloadComplete(downloadedFile);
                } else {
                    showDownloadFailed();
                }
            });
        });
    }

    private void showDownloadComplete(Path downloadedFile) {
        progressBar.setProgress(1.0);
        statusLabel.setText("Download complete!");
        statusLabel.setStyle("-fx-text-fill: #2e7d32;");

        downloadButton.setText("Apply & Exit");
        downloadButton.setDisable(false);
        downloadButton.setOnAction(e -> {
            dialog.close();
            updateService.applyUpdateAndExit(downloadedFile);
        });

        skipButton.setText("Update Later");
        skipButton.setVisible(true);
        skipButton.setDisable(false);
        skipButton.setOnAction(e -> dialog.close());

        // Show success message with file location
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Update Downloaded");
        alert.setHeaderText("Update Downloaded Successfully!");
        alert.setContentText(
                "The new version has been downloaded.\n\n" +
                        "Click 'Apply & Exit' to update and close the app. You can then reopen it manually.");
        alert.initOwner(dialog);
        alert.showAndWait();
    }

    private void showDownloadFailed() {
        progressBar.setVisible(false);
        statusLabel.setText("Download failed. Please try again later.");
        statusLabel.setStyle("-fx-text-fill: #c62828;");

        downloadButton.setText("Retry");
        downloadButton.setDisable(false);
        downloadButton.setOnAction(e -> startDownload());
        skipButton.setDisable(false);
    }

    public void show() {
        dialog.showAndWait();
    }
}

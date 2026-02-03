package com.arif2fast.views;

import com.arif2fast.models.FileInfoDTO;
import com.arif2fast.models.ExecutionResult;
import com.arif2fast.services.FileManagementService;
import com.arif2fast.services.ScriptExecutorService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FileManagerController {

    @FXML
    private TextField filePathTextField;

    @FXML
    private ComboBox<String> moduleComboBox;

    @FXML
    private ComboBox<String> versionComboBox;

    @FXML
    private Button browseButton;

    @FXML
    private Button uploadButton;

    @FXML
    private Button refreshButton;

    @FXML
    private Button deleteButton;

    @FXML
    private TreeView<String> fileTreeView;

    @FXML
    private Label statusLabel;

    @FXML
    private MenuButton updateModulesMenuButton;

    @FXML
    private Button executeUpdateButton;

    private final FileManagementService fileManagementService;
    private final ScriptExecutorService scriptExecutorService;

    @Value("${file.manager.default.versions}")
    private String defaultVersions;

    @Value("${liquibase.home.directory}")
    private String executeDirectory;

    private List<File> selectedFiles = new ArrayList<>();
    private BooleanProperty isFileSelected = new SimpleBooleanProperty(false);

    public FileManagerController(FileManagementService fileManagementService,
            ScriptExecutorService scriptExecutorService) {
        this.fileManagementService = fileManagementService;
        this.scriptExecutorService = scriptExecutorService;
    }

    @FXML
    public void initialize() {
        log.info("Initializing FileManagerController");

        List<String> modules = fileManagementService.getModules();
        moduleComboBox.getItems().addAll(modules);

        // Initialize multi-select menu button for update command
        updateModulesMenuButton.getItems().clear();
        for (String mod : modules) {
            CheckMenuItem item = new CheckMenuItem(mod);
            item.selectedProperty().addListener((obs, wasSelected, isSelected) -> updateMenuButtonText());
            updateModulesMenuButton.getItems().add(item);
        }
        updateMenuButtonText();

        String[] versions = defaultVersions.split(",");
        versionComboBox.getItems().addAll(versions);

        // Enable multiple selection in TreeView
        fileTreeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Bind upload button disable state
        uploadButton.disableProperty().bind(
                isFileSelected.not()
                        .or(moduleComboBox.valueProperty().isNull())
                        .or(versionComboBox.valueProperty().isNull()));

        // Bind delete button disable state
        deleteButton.disableProperty().bind(
                Bindings.isEmpty(fileTreeView.getSelectionModel().getSelectedItems()));

        // Update button is always enabled
        // executeUpdateButton.setDisable(true);

        loadFileTree();

        updateStatus("Ready");
    }

    @FXML
    private void handleBrowse() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Files to Upload");

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Supported Files", "*.sqlx", "*.yaml"),
                new FileChooser.ExtensionFilter("SQLX Files", "*.sqlx"),
                new FileChooser.ExtensionFilter("YAML Files", "*.yaml"));

        List<File> files = fileChooser.showOpenMultipleDialog(browseButton.getScene().getWindow());

        if (files != null && !files.isEmpty()) {
            selectedFiles = files;
            isFileSelected.set(true);
            if (files.size() == 1) {
                filePathTextField.setText(files.get(0).getAbsolutePath());
            } else {
                filePathTextField.setText(files.size() + " files selected");
            }
            updateStatus(files.size() + " files selected");
        } else {
            isFileSelected.set(false);
        }
    }

    @FXML
    private void handleUpload() {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            showError("Please select at least one file to upload");
            return;
        }

        String module = moduleComboBox.getValue();
        if (module == null || module.trim().isEmpty()) {
            showError("Please select a module");
            return;
        }

        String version = versionComboBox.getValue();
        if (version == null || version.trim().isEmpty()) {
            showError("Please select or enter a version");
            return;
        }

        try {
            int successCount = 0;
            for (File file : selectedFiles) {
                fileManagementService.uploadFile(file, module, version);
                successCount++;
            }

            updateStatus(successCount + " files uploaded successfully");

            selectedFiles = new ArrayList<>();
            filePathTextField.clear();

            loadFileTree();

            showInfo("Upload Successful", successCount + " files uploaded to " + module + "/" + version);

        } catch (Exception e) {
            log.error("Error uploading files", e);
            showError("Upload failed: " + e.getMessage());
        }
    }

    @FXML
    private void handleRefresh() {
        loadFileTree();
        updateStatus("File list refreshed");
    }

    @FXML
    private void handleAllDelete() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Delete All");
        confirmation.setHeaderText("Delete All Files");
        confirmation.setContentText("Are you sure you want to delete ALL files? This action cannot be undone.");

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            updateStatus("Executing delete all yaml/sqlx files");

            new Thread(() -> {
                List<String> results = fileManagementService.cleanupYamlFiles();
                String output = String.join("\n", results);

                Platform.runLater(() -> {
                    loadFileTree();
                    showScrollableInfo("Delete All Successful", "Files processed during cleanup:", output);
                    updateStatus("All files deleted");
                });
            }).start();
        }
    }

    @FXML
    private void handleDelete() {
        List<TreeItem<String>> selectedItems = new ArrayList<>(fileTreeView.getSelectionModel().getSelectedItems());

        if (selectedItems.isEmpty()) {
            showError("Please select items to delete");
            return;
        }
        List<Path> filesToDelete = selectedItems.stream()
                .map(fileManagementService::getPathFromTreeItem)
                .filter(Objects::nonNull)
                .filter(Files::isRegularFile)
                .toList();

        int totalSelected = selectedItems.size();
        int filesCount = filesToDelete.size();
        int foldersCount = totalSelected - filesCount;

        if (filesCount == 0) {
            showInfo("Deletion Not Allowed", "You selected " + foldersCount + " folder(s).\n" +
                    "Deleting modules or folders is not allowed. Please select individual files to delete.");
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Delete");
        confirmation.setHeaderText("Delete Selected Files");

        String contentText = "Are you sure you want to delete " + filesCount + " selected file(s)?";
        if (foldersCount > 0) {
            contentText += "\nNote: " + foldersCount + " folder(s) in your selection will be ignored.";
        }
        confirmation.setContentText(contentText);

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            int successCount = 0;
            int failCount = 0;

            for (Path path : filesToDelete) {
                String fileName = path.getFileName().toString();

                String moduleName = "XXXX";
                try {
                    Path relative = Paths.get(fileManagementService.getLiquibaseDirectory()).relativize(path);
                    if (relative.getNameCount() > 0) {
                        moduleName = relative.getName(0).toString();
                    }
                } catch (Exception e) {
                    log.error("Could not determine module name for path {}", path, e);
                }

                if (fileManagementService.deleteRecursive(path)) {
                    successCount++;
                    // Also delete from project directory
                    if (fileManagementService.getModules().contains(moduleName)) {
                        fileManagementService.deleteFileFromProject(moduleName, fileName);
                    }
                } else {
                    failCount++;
                }
            }

            loadFileTree();

            if (failCount == 0) {
                String msg = "Success: " + successCount + " files deleted.";
                if (foldersCount > 0) {
                    msg += " (" + foldersCount + " folders skipped)";
                }
                showInfo("Delete Successful", msg);
            } else {
                showError("Delete partially failed. Success: " + successCount + ", Failed: " + failCount);
            }
        }
    }

    @FXML
    private void handleExecuteUpdate() {
        List<String> selectedModules = updateModulesMenuButton.getItems().stream()
                .filter(item -> item instanceof CheckMenuItem && ((CheckMenuItem) item).isSelected())
                .map(MenuItem::getText)
                .toList();

        String modulesDisplay = selectedModules.isEmpty() ? "ALL" : String.join(", ", selectedModules);
        updateStatus("Executing update for: " + modulesDisplay);

        // Create the dialog with TextArea that will update in real-time
        Dialog<Void> outputDialog = new Dialog<>();
        outputDialog.setTitle("Liquibase Update - Live Output");
        outputDialog.setHeaderText("Executing: liquibase --prop-env=IDEV" +
                (selectedModules.isEmpty() ? "" : " --include-modules=" + String.join(",", selectedModules)) +
                " update\nModules: " + modulesDisplay);
        outputDialog.setResizable(true);

        TextArea outputTextArea = new TextArea();
        outputTextArea.setEditable(false);
        outputTextArea.setWrapText(true);
        outputTextArea.setPrefWidth(700);
        outputTextArea.setPrefHeight(400);
        outputTextArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");

        // Initial message
        outputTextArea.appendText("Starting execution...\n");
        outputTextArea.appendText("==================================================\n\n");

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.getChildren().add(outputTextArea);
        outputDialog.getDialogPane().setContent(content);

        // Add Close button (initially disabled while running)
        ButtonType closeButtonType = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        outputDialog.getDialogPane().getButtonTypes().add(closeButtonType);
        Button closeButton = (Button) outputDialog.getDialogPane().lookupButton(closeButtonType);
        closeButton.setDisable(true);

        // Show dialog (non-blocking)
        outputDialog.show();

        // Run the command in background thread with real-time output
        new Thread(() -> {
            ExecutionResult result = scriptExecutorService.executeUpdateCommandWithRealtimeOutput(
                    selectedModules,
                    executeDirectory,
                    line -> {
                        // This consumer is called for each line of output
                        Platform.runLater(() -> {
                            outputTextArea.appendText(line + "\n");
                            // Auto-scroll to bottom
                            outputTextArea.setScrollTop(Double.MAX_VALUE);
                        });
                    });

            Platform.runLater(() -> {
                // Append final status
                outputTextArea.appendText("\n==================================================\n");
                outputTextArea.appendText("Exit Code: " + result.getExitCode() + "\n");
                outputTextArea.appendText(result.isSuccess() ? "Liquibase update finished successfully."
                        : "Liquibase update finished with errors.");

                // Enable close button
                closeButton.setDisable(false);

                // Update header to show completion status
                outputDialog.setHeaderText(outputDialog.getHeaderText() +
                        "\n\n" + (result.isSuccess() ? "✓ Completed successfully" : "✗ Completed with errors"));

                if (result.isSuccess()) {
                    updateStatus("Update command completed successfully");
                } else {
                    updateStatus("Update command failed");
                }
            });
        }).start();
    }

    private void updateMenuButtonText() {
        long count = updateModulesMenuButton.getItems().stream()
                .filter(item -> item instanceof CheckMenuItem && ((CheckMenuItem) item).isSelected())
                .count();
        // Button is always enabled, empty selection means "all modules"
        // executeUpdateButton.setDisable(count == 0);

        if (count == 0) {
            updateModulesMenuButton.setText("Select Modules");
        } else {
            List<String> selected = updateModulesMenuButton.getItems().stream()
                    .filter(item -> item instanceof CheckMenuItem && ((CheckMenuItem) item).isSelected())
                    .map(MenuItem::getText)
                    .toList();

            if (selected.size() <= 4) {
                updateModulesMenuButton.setText(String.join(", ", selected));
            } else {
                updateModulesMenuButton.setText(count + " Modules Selected");
            }
        }
    }

    private void loadFileTree() {
        List<FileInfoDTO> allFiles = fileManagementService.getAllFiles();

        TreeItem<String> root = new TreeItem<>("Files");
        root.setExpanded(true);

        Map<String, List<FileInfoDTO>> groupedFiles = allFiles.stream()
                .collect(Collectors.groupingBy(
                        FileInfoDTO::getModuleName));

        groupedFiles.forEach((version, files) -> {
            TreeItem<String> moduleNode = new TreeItem<>(version);

            files.forEach(file -> {
                TreeItem<String> fileNode = new TreeItem<>(file.getFileName());
                moduleNode.getChildren().add(fileNode);
            });

            root.getChildren().add(moduleNode);
        });

        fileTreeView.setRoot(root);
    }

    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
        updateStatus("Error: " + message);
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showScrollableInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        javafx.scene.layout.GridPane.setVgrow(textArea, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.GridPane.setHgrow(textArea, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.GridPane expContent = new javafx.scene.layout.GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(textArea, 0, 0);

        alert.getDialogPane().setExpandableContent(expContent);
        alert.getDialogPane().setExpanded(true);

        alert.showAndWait();
    }

}

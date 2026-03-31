package com.arif2fast.views;

import com.arif2fast.models.FileInfoDTO;
import com.arif2fast.models.ExecutionResult;
import com.arif2fast.services.FileManagementService;
import com.arif2fast.services.ScriptExecutorService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.geometry.Pos;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
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
import java.util.prefs.Preferences;
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
    private Button executeUpdateButton;

    private final FileManagementService fileManagementService;
    private final ScriptExecutorService scriptExecutorService;

    @Value("${file.manager.default.versions}")
    private String defaultVersions;

    @Value("${liquibase.home.directory}")
    private String executeDirectory;

    private List<File> selectedFiles = new ArrayList<>();
    private BooleanProperty isFileSelected = new SimpleBooleanProperty(false);
    private final Preferences prefs = Preferences.userNodeForPackage(FileManagerController.class);
    private static final String LAST_BROWSE_DIR = "last_browse_dir";

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

        // Add double click to edit file
        fileTreeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<String> item = fileTreeView.getSelectionModel().getSelectedItem();
                if (item != null && item.isLeaf() && item.getParent() != null && !item.getParent().getValue().equals("Files")) {
                    handleEditFile(item);
                }
            }
        });

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

        String lastDir = prefs.get(LAST_BROWSE_DIR, null);
        if (lastDir != null) {
            File dir = new File(lastDir);
            if (dir.exists() && dir.isDirectory()) {
                fileChooser.setInitialDirectory(dir);
            }
        }

        List<File> files = fileChooser.showOpenMultipleDialog(browseButton.getScene().getWindow());

        if (files != null && !files.isEmpty()) {
            prefs.put(LAST_BROWSE_DIR, files.get(0).getParentFile().getAbsolutePath());
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

    private void handleEditFile(TreeItem<String> item) {
        Path path = fileManagementService.getPathFromTreeItem(item);
        if (path == null || !Files.isRegularFile(path)) {
            showError("Cannot read file.");
            return;
        }

        String fileName = path.getFileName().toString();
        String moduleName = "unknown";
        try {
            Path relative = Paths.get(fileManagementService.getLiquibaseDirectory()).relativize(path);
            if (relative.getNameCount() > 0) {
                moduleName = relative.getName(0).toString();
            }
        } catch (Exception e) {
            log.error("Could not determine module name", e);
        }

        try {
            String content = Files.readString(path);
            showFileEditorDialog(moduleName, fileName, path, content);
        } catch (Exception e) {
            log.error("Error reading file", e);
            showError("Error reading file: " + e.getMessage());
        }
    }

    private void showFileEditorDialog(String moduleName, String fileName, Path liquibasePath, String content) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Edit File");
        dialog.setHeaderText("Editing: " + moduleName + " / " + fileName);
        dialog.setResizable(true);

        TextArea textArea = new TextArea(content);
        textArea.setPrefWidth(800);
        textArea.setPrefHeight(600);
        textArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");

        VBox box = new VBox(textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);

        ButtonType saveBtnType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtnType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, cancelBtnType);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == saveBtnType) {
                return textArea.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newContent -> {
            try {
                // Save to Liquibase Directory
                Files.writeString(liquibasePath, newContent);
                // Save to Project Directory (sync)
                if (!moduleName.equals("unknown")) {
                    fileManagementService.saveContentToProject(moduleName, fileName, newContent);
                }
                updateStatus("File saved: " + fileName);
                showInfo("Save Successful", "File changes have been saved to both Liquibase and Project directories.");
            } catch (Exception e) {
                log.error("Error saving file: {}", fileName, e);
                showError("Failed to save file: " + e.getMessage());
            }
        });
    }

    @FXML
    private void handleAllDelete() {
        Optional<ButtonType> result = showDeleteConfirmation("Confirm Delete All",
                "Delete All Files",
                "Are you sure you want to delete ALL files? This action cannot be undone.");

        if (result.isPresent() && (result.get().getText().equals("Delete Both") || result.get().getText().equals("Delete List Only"))) {
            boolean deleteFromProject = result.get().getText().equals("Delete Both");
            updateStatus("Executing delete all yaml/sqlx files" + (deleteFromProject ? " (including project files)" : ""));

            new Thread(() -> {
                List<String> results = fileManagementService.cleanupYamlFiles(deleteFromProject);
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

        String contentText = "Are you sure you want to delete " + filesCount + " selected file(s)?";
        if (foldersCount > 0) {
            contentText += "\nNote: " + foldersCount + " folder(s) in your selection will be ignored.";
        }

        Optional<ButtonType> result = showDeleteConfirmation("Confirm Delete", "Delete Selected Files", contentText);

        if (result.isPresent() && (result.get().getText().equals("Delete Both") || result.get().getText().equals("Delete List Only"))) {
            boolean deleteFromProject = result.get().getText().equals("Delete Both");
            int successCount = 0;
            int failCount = 0;

            for (Path path : filesToDelete) {
                String fileName = path.getFileName().toString();

                // Prevent deletion of protected file
                if (fileName.equalsIgnoreCase("9999995_ADD_NATIVE_YAML_SQL_NATIVE_YAML_0000_CTBCTW.yaml")) {
                    log.warn("Attempted to delete protected file: {}", fileName);
                    failCount++;
                    continue;
                }

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
                    // Also delete from project directory if requested
                    if (deleteFromProject && fileManagementService.getModules().contains(moduleName)) {
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

    private Optional<ButtonType> showDeleteConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("System Confirmation");

        // Create buttons
        ButtonType deleteBoth = new ButtonType("Delete Both", ButtonBar.ButtonData.OK_DONE);
        ButtonType deleteListOnly = new ButtonType("Delete List Only", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(deleteBoth, deleteListOnly, cancel);

        // Header Section
        Label headerLabel = new Label(header);
        headerLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        headerLabel.setTextFill(Color.web("#d32f2f")); // Professional red

        // Content Section
        Text contentText = new Text(content);
        contentText.setWrappingWidth(400);
        contentText.setFont(Font.font("Segoe UI", 14));

        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(headerLabel, contentText);
        contentBox.setPadding(new javafx.geometry.Insets(0, 0, 0, 15));

        // Add a warning icon/symbol (using a large text character or system icon)
        Label iconLabel = new Label("⚠");
        iconLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 48));
        iconLabel.setTextFill(Color.web("#fbc02d")); // Professional Amber

        HBox mainLayout = new HBox(10);
        mainLayout.setAlignment(Pos.CENTER_LEFT);
        mainLayout.setPadding(new javafx.geometry.Insets(20));
        mainLayout.getChildren().addAll(iconLabel, contentBox);

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setContent(mainLayout);
        dialogPane.setPrefWidth(600);
        dialogPane.setMinHeight(Region.USE_PREF_SIZE);

        // Add some basic styling to buttons via the dialog pane's lookup
        Platform.runLater(() -> {
            Button btnBoth = (Button) dialogPane.lookupButton(deleteBoth);
            if (btnBoth != null) {
                btnBoth.setStyle("-fx-background-color: #d32f2f; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15 8 15;");
                btnBoth.setMinWidth(140);
            }
            Button btnList = (Button) dialogPane.lookupButton(deleteListOnly);
            if (btnList != null) {
                btnList.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #bdbdbd; -fx-font-weight: bold; -fx-padding: 8 15 8 15;");
                btnList.setMinWidth(140);
            }
            Button btnCancel = (Button) dialogPane.lookupButton(cancel);
            if (btnCancel != null) {
                btnCancel.setStyle("-fx-padding: 8 15 8 15;");
                btnCancel.setMinWidth(100);
            }

            // Force window to resize to its content to prevent clipping
            if (dialogPane.getScene() != null && dialogPane.getScene().getWindow() != null) {
                dialogPane.getScene().getWindow().sizeToScene();
            }
        });

        return alert.showAndWait();
    }

    @FXML
    private void handleExecuteUpdate() {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Execution Mode");
        dialog.setHeaderText("Select Update Execution Mode");

        Label autoDesc = new Label("Automatically detects and executes modules containing valid YAML or SQLX scripts. Skips protected YAML.");
        autoDesc.setWrapText(true);
        autoDesc.setStyle("-fx-text-fill: #555555; -fx-font-size: 11px; -fx-padding: 0 0 5 25;");

        Label manualDesc = new Label("Select exactly which modules to execute from the dropdown list manually. None Selected means all modules will be executed.");
        manualDesc.setWrapText(true);
        manualDesc.setStyle("-fx-text-fill: #555555; -fx-font-size: 11px; -fx-padding: 0 0 5 25;");

        ToggleGroup group = new ToggleGroup();
        RadioButton autoRadio = new RadioButton("Auto Execute (Smart Mode)");
        autoRadio.setStyle("-fx-font-weight: bold; -fx-text-fill: #333333;");
        autoRadio.setToggleGroup(group);
        autoRadio.setSelected(true);

        RadioButton manualRadio = new RadioButton("Manual Execute");
        manualRadio.setStyle("-fx-font-weight: bold; -fx-text-fill: #333333;");
        manualRadio.setToggleGroup(group);

        VBox autoBox = new VBox(2, autoRadio, autoDesc);
        VBox manualBox = new VBox(2, manualRadio, manualDesc);

        MenuButton modulesMenuButton = new MenuButton("Select Modules");
        modulesMenuButton.setMaxWidth(Double.MAX_VALUE);
        modulesMenuButton.setDisable(true);
        modulesMenuButton.setStyle("-fx-font-size: 13px; -fx-padding: 5;");

        List<String> modules = fileManagementService.getModules();
        for (String mod : modules) {
            CheckMenuItem item = new CheckMenuItem(mod);
            item.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                long count = modulesMenuButton.getItems().stream()
                        .filter(i -> i instanceof CheckMenuItem && ((CheckMenuItem) i).isSelected())
                        .count();
                if (count == 0) {
                    modulesMenuButton.setText("Select Modules");
                } else {
                    List<String> selected = modulesMenuButton.getItems().stream()
                            .filter(i -> i instanceof CheckMenuItem && ((CheckMenuItem) i).isSelected())
                            .map(MenuItem::getText)
                            .toList();
                    if (selected.size() <= 4) {
                        modulesMenuButton.setText(String.join(", ", selected));
                    } else {
                        modulesMenuButton.setText(count + " Modules Selected");
                    }
                }
            });
            modulesMenuButton.getItems().add(item);
        }

        manualRadio.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            modulesMenuButton.setDisable(!isSelected);
        });

        HBox manualControlLayout = new HBox(modulesMenuButton);
        manualControlLayout.setPadding(new javafx.geometry.Insets(0, 0, 0, 25));
        HBox.setHgrow(modulesMenuButton, Priority.ALWAYS);

        VBox combinedManualBox = new VBox(8, manualBox, manualControlLayout);

        VBox contentBox = new VBox(15, autoBox, combinedManualBox);
        contentBox.setPadding(new javafx.geometry.Insets(15, 20, 15, 20));
        contentBox.setStyle("-fx-background-color: transparent;");

        dialog.getDialogPane().setContent(contentBox);
        dialog.getDialogPane().setPrefWidth(550);
        dialog.getDialogPane().setMinHeight(300);

        ButtonType executeBtn = new ButtonType("Execute", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(executeBtn, cancelBtn);

        Platform.runLater(() -> {
            Button btnExecute = (Button) dialog.getDialogPane().lookupButton(executeBtn);
            if (btnExecute != null) {
                btnExecute.setStyle("-fx-background-color: #1976d2; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15 8 15;");
                btnExecute.setMinWidth(120);
            }
            Button btnCancel = (Button) dialog.getDialogPane().lookupButton(cancelBtn);
            if (btnCancel != null) {
                btnCancel.setStyle("-fx-padding: 8 15 8 15;");
                btnCancel.setMinWidth(100);
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType == executeBtn) {
                if (autoRadio.isSelected()) {
                    List<String> autoModules = fileManagementService.getAllFiles().stream()
                            .filter(f -> {
                                String name = f.getFileName().toLowerCase();
                                boolean isYamlOrSqlx = name.endsWith(".yaml") || name.endsWith(".sqlx");
                                boolean isProtected = f.getFileName().startsWith("9999995");
                                return isYamlOrSqlx && !isProtected;
                            })
                            .map(FileInfoDTO::getModuleName)
                            .distinct()
                            .toList();
                    
                    if (autoModules.isEmpty()) {
                        return new ArrayList<>(Collections.singletonList("AUTO_EMPTY_SKIP"));
                    }
                    return autoModules;
                } else {
                    return modulesMenuButton.getItems().stream()
                            .filter(item -> item instanceof CheckMenuItem && ((CheckMenuItem) item).isSelected())
                            .map(MenuItem::getText)
                            .toList();
                }
            }
            return null;
        });

        Optional<List<String>> dialogResult = dialog.showAndWait();

        if (dialogResult.isEmpty()) {
            return;
        }

        List<String> selectedModules = dialogResult.get();
        boolean isAuto = autoRadio.isSelected();

        if (isAuto && selectedModules.size() == 1 && "AUTO_EMPTY_SKIP".equals(selectedModules.get(0))) {
            showInfo("Auto Execute", "No eligible modules found. (Only found protected files or no YAML/SQLX files)");
            return;
        }

        String modulesDisplay = (selectedModules.isEmpty() && !isAuto) ? "ALL" : String.join(", ", selectedModules);
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

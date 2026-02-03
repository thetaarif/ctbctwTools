package com.arif2fast.services;

import com.arif2fast.models.FileInfoDTO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javafx.scene.control.TreeItem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class FileManagementService {

    @Value("${file.manager.project.directory}")
    private String projectDirectory;

    @Getter
    @Value("${file.manager.liquibase.directory}")
    private String liquibaseDirectory;

    @Value("${file.manager.allowed.extensions}")
    private String allowedExtensions;

    @Value("${file.manager.modules}")
    private String modules;

    public List<String> getModules() {
        return Arrays.asList(modules.split(","));
    }

    public boolean isValidExtension(String fileName) {
        String[] extensions = allowedExtensions.split(",");
        return Arrays.stream(extensions)
                .anyMatch(ext -> fileName.toLowerCase().endsWith(ext.toLowerCase()));
    }

    public void uploadFile(File sourceFile, String moduleName, String version) throws IOException {
        if (!isValidExtension(sourceFile.getName())) {
            throw new IllegalArgumentException("Invalid file extension. Allowed: " + allowedExtensions);
        }

        String fileName = sourceFile.getName();

        boolean isYaml = fileName.toLowerCase().endsWith(".yaml");

        Path projectPath = isYaml
                ? Paths.get(projectDirectory, moduleName, "9.0.0.X", version, fileName)
                : Paths.get(projectDirectory, moduleName, "9.0.0.X", version, "oracle", fileName);

        Files.createDirectories(projectPath.getParent());
        Files.copy(sourceFile.toPath(), projectPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("File uploaded to project: {}", projectPath);

        Path liquibasePath = isYaml
                ? Paths.get(liquibaseDirectory, moduleName, "yaml", fileName)
                : Paths.get(liquibaseDirectory, moduleName, "yaml", "oracle", fileName);

        Files.createDirectories(liquibasePath.getParent());
        Files.copy(sourceFile.toPath(), liquibasePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("File uploaded to liquibase: {}", liquibasePath);

    }

    public List<FileInfoDTO> getAllFiles() {
        List<FileInfoDTO> allFiles = new ArrayList<>();
        getModules().forEach(m -> allFiles.addAll(scanDirectory(Paths.get(liquibaseDirectory, m, "yaml").toString())));
        return allFiles;
    }

    private List<FileInfoDTO> scanDirectory(String baseDir) {
        List<FileInfoDTO> files = new ArrayList<>();
        Path basePath = Paths.get(baseDir);

        if (!Files.exists(basePath)) {
            return files;
        }

        try (Stream<Path> paths = Files.walk(basePath)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isValidExtension(path.getFileName().toString()))
                    .map(path -> createFileInfoDTO(path, basePath))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("Error scanning directory: {}", baseDir, e);
        }

        return files;
    }

    private FileInfoDTO createFileInfoDTO(Path filePath, Path basePath) {
        String[] parts = (basePath.toString().split("yamls")[1]).split("[\\\\/]");

        String moduleName = parts.length > 1 ? parts[1] : "unknown";

        return FileInfoDTO.builder()
                .moduleName(moduleName)
                .fileName(filePath.getFileName().toString())
                .filePath(filePath)
                .build();
    }

    public boolean deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            } else {
                Files.deleteIfExists(path);
            }
            log.info("Path deleted recursively: {}", path);
            return true;
        } catch (IOException e) {
            log.error("Error deleting path recursively: {}", path, e);
            return false;
        }
    }

    public void deleteFileFromProject(String moduleName, String fileName) {
        Path moduleProjectDir = Paths.get(projectDirectory, moduleName, "9.0.0.X");
        if (!Files.exists(moduleProjectDir)) {
            log.warn("Project directory for module {} does not exist: {}", moduleName, moduleProjectDir);
            return;
        }

        try (Stream<Path> walk = Files.walk(moduleProjectDir)) {
            List<Path> matches = walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .toList();

            for (Path p : matches) {
                try {
                    Files.deleteIfExists(p);
                    log.info("Deleted sync file from project directory: {}", p);
                } catch (IOException e) {
                    log.error("Failed to delete sync file: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("Error walking project directory for deletion: {}", moduleProjectDir, e);
        }
    }

    public Path getPathFromTreeItem(TreeItem<String> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        Optional<FileInfoDTO> fileInfo = findFileByItem(item);
        return fileInfo.map(FileInfoDTO::getFilePath).orElse(null);
    }

    private Optional<FileInfoDTO> findFileByItem(TreeItem<String> item) {
        String fileName = item.getValue();
        String module = item.getParent().getValue();

        return scanDirectory(Paths.get(liquibaseDirectory, module, "yaml").toString()).stream()
                .filter(f -> f.getFileName().equals(fileName)
                        && f.getModuleName().equals(module))
                .findFirst();
    }

    public List<String> cleanupYamlFiles() {
        List<String> resultMessages = new ArrayList<>();
        Path rootPath = Paths.get(liquibaseDirectory);

        if (!Files.exists(rootPath)) {
            resultMessages.add("Root directory not found: " + liquibaseDirectory);
            return resultMessages;
        }

        try (Stream<Path> paths = Files.walk(rootPath)) {
            List<Path> filesToDelete = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".yaml") || fileName.endsWith(".sqlx");
                    })
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        // Check if starts with digit
                        if (fileName.isEmpty() || !Character.isDigit(fileName.charAt(0))) {
                            return false;
                        }
                        // Exclude 9999995*
                        if (fileName.startsWith("9999995")) {
                            return false;
                        }
                        // Exclude master-changelog*
                        if (fileName.toLowerCase().startsWith("master-changelog")) {
                            return false;
                        }
                        return true;
                    })
                    .toList();

            for (Path path : filesToDelete) {
                try {
                    Files.delete(path);
                    resultMessages.add("Deleting file: " + path);
                    log.info("Deleted file during cleanup: {}", path);
                } catch (IOException e) {
                    String errorMsg = "Failed to delete file: " + path + " (" + e.getMessage() + ")";
                    resultMessages.add(errorMsg);
                    log.error(errorMsg, e);
                }
            }
        } catch (IOException e) {
            log.error("Error walking directory for cleanup: {}", liquibaseDirectory, e);
            resultMessages.add("Error scanning directory: " + e.getMessage());
        }

        if (resultMessages.isEmpty()) {
            resultMessages.add("No matching files found to delete.");
        }

        return resultMessages;
    }
}

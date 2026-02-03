package com.arif2fast.services;

import com.arif2fast.models.UpdateInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Service for checking and downloading application updates from GitHub
 * Releases.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UpdateService {

    private static final String LOG_PREFIX = "AutoUpdate: ";
    private static final String USER_AGENT = "ctbctwTools-Updater";

    private final UpdateConfig config;
    private final Gson gson = new Gson();

    private String currentVersion;

    /**
     * Initialize the service with the current application version.
     */
    public void setCurrentVersion(String version) {
        this.currentVersion = version;
    }

    /**
     * Check for updates asynchronously.
     */
    public CompletableFuture<Optional<UpdateInfo>> checkForUpdateAsync() {
        return CompletableFuture.supplyAsync(this::checkForUpdate);
    }

    /**
     * Check for updates synchronously using GitHub Releases API.
     */
    public Optional<UpdateInfo> checkForUpdate() {
        if (!config.isEnabled()) {
            log.info(LOG_PREFIX + "Update check is disabled.");
            return Optional.empty();
        }

        if (!config.isGitHubConfigured()) {
            log.info(LOG_PREFIX + "GitHub repository not configured, skipping update check.");
            return Optional.empty();
        }

        String apiUrl = config.getGitHubApiUrl();
        log.info(LOG_PREFIX + "Checking for updates at: " + apiUrl);

        try {
            UpdateInfo latestVersion = fetchLatestRelease(apiUrl);
            if (latestVersion == null || latestVersion.getVersion() == null) {
                log.warn(LOG_PREFIX + "Could not parse release info from GitHub.");
                return Optional.empty();
            }

            log.info(LOG_PREFIX + "Current version: " + currentVersion +
                    ", Latest version: " + latestVersion.getVersion());

            if (isNewerVersion(latestVersion.getVersion(), currentVersion)) {
                log.info(LOG_PREFIX + "Update available: " + latestVersion.getVersion());
                return Optional.of(latestVersion);
            } else {
                log.info(LOG_PREFIX + "Application is up to date.");
                return Optional.empty();
            }

        } catch (Exception e) {
            log.warn(LOG_PREFIX + "Failed to check for updates: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch latest release info from GitHub API.
     */
    private UpdateInfo fetchLatestRelease(String apiUrl) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(config.getTimeoutSeconds() * 1000);
        connection.setReadTimeout(config.getTimeoutSeconds() * 1000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("GitHub API returned HTTP " + responseCode);
        }

        String responseBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }

        return parseGitHubRelease(responseBody);
    }

    /**
     * Parse GitHub release JSON response.
     */
    private UpdateInfo parseGitHubRelease(String json) {
        JsonObject release = gson.fromJson(json, JsonObject.class);

        // Get version from tag_name (strip 'v' prefix if present)
        String tagName = release.get("tag_name").getAsString();
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        // Get release notes
        String releaseNotes = release.has("body") && !release.get("body").isJsonNull()
                ? release.get("body").getAsString()
                : "";

        String releaseName = release.has("name") && !release.get("name").isJsonNull()
                ? release.get("name").getAsString()
                : "Release " + version;

        // Find JAR asset matching pattern
        JsonArray assets = release.getAsJsonArray("assets");
        String downloadUrl = null;
        long fileSize = 0;

        Pattern pattern = createAssetPattern(config.getAssetPattern());

        for (int i = 0; i < assets.size(); i++) {
            JsonObject asset = assets.get(i).getAsJsonObject();
            String assetName = asset.get("name").getAsString();

            if (pattern.matcher(assetName).matches()) {
                downloadUrl = asset.get("browser_download_url").getAsString();
                fileSize = asset.get("size").getAsLong();
                log.info(LOG_PREFIX + "Found matching asset: " + assetName);
                break;
            }
        }

        if (downloadUrl == null) {
            log.warn(LOG_PREFIX + "No JAR asset found matching pattern: " + config.getAssetPattern());
            return null;
        }

        return UpdateInfo.builder()
                .version(version)
                .downloadUrl(downloadUrl)
                .releaseNotes(releaseNotes)
                .releaseName(releaseName)
                .fileSize(fileSize)
                .build();
    }

    /**
     * Convert glob-like pattern to regex.
     */
    private Pattern createAssetPattern(String globPattern) {
        String regex = globPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * Download the update JAR file.
     */
    public Optional<Path> downloadUpdate(UpdateInfo updateInfo, Consumer<Double> progressCallback) {
        if (updateInfo == null || updateInfo.getDownloadUrl() == null) {
            log.error(LOG_PREFIX + "No download URL provided.");
            return Optional.empty();
        }

        log.info(LOG_PREFIX + "Downloading update from: " + updateInfo.getDownloadUrl());

        try {
            URL url = new URL(updateInfo.getDownloadUrl());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(config.getTimeoutSeconds() * 1000);
            connection.setReadTimeout(120000); // 2 minutes for download
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log.error(LOG_PREFIX + "Download failed with HTTP " + responseCode);
                return Optional.empty();
            }

            long contentLength = updateInfo.getFileSize() > 0
                    ? updateInfo.getFileSize()
                    : connection.getContentLengthLong();
            log.info(LOG_PREFIX + "Download size: " + (contentLength > 0 ? contentLength + " bytes" : "unknown"));

            // Create temp file for download
            Path downloadDir = Path.of(config.getDownloadDir());
            Files.createDirectories(downloadDir);
            Path tempFile = downloadDir.resolve("ctbctwTools-" + updateInfo.getVersion() + ".jar");

            try (InputStream in = connection.getInputStream();
                    OutputStream out = Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    if (progressCallback != null && contentLength > 0) {
                        progressCallback.accept((double) downloaded / contentLength);
                    }
                }
            }

            log.info(LOG_PREFIX + "Update downloaded to: " + tempFile);
            return Optional.of(tempFile);

        } catch (Exception e) {
            log.error(LOG_PREFIX + "Failed to download update: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Download update asynchronously.
     */
    public CompletableFuture<Optional<Path>> downloadUpdateAsync(UpdateInfo updateInfo,
            Consumer<Double> progressCallback) {
        return CompletableFuture.supplyAsync(() -> downloadUpdate(updateInfo, progressCallback));
    }

    /**
     * Compare two version strings (semantic versioning).
     */
    public boolean isNewerVersion(String newVersion, String currentVersion) {
        if (newVersion == null || currentVersion == null) {
            return false;
        }

        // Strip 'v' prefix if present
        newVersion = newVersion.startsWith("v") ? newVersion.substring(1) : newVersion;
        currentVersion = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;

        try {
            String[] newParts = newVersion.split("\\.");
            String[] currentParts = currentVersion.split("\\.");

            int maxLength = Math.max(newParts.length, currentParts.length);

            for (int i = 0; i < maxLength; i++) {
                int newPart = i < newParts.length ? Integer.parseInt(newParts[i].trim()) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i].trim()) : 0;

                if (newPart > currentPart) {
                    return true;
                } else if (newPart < currentPart) {
                    return false;
                }
            }

            return false;

        } catch (NumberFormatException e) {
            log.warn(LOG_PREFIX + "Could not parse version numbers: " + e.getMessage());
            return newVersion.compareTo(currentVersion) > 0;
        }
    }

    /**
     * Applies the update and exits the application.
     * Works by creating a temporary batch script that waits for the current process
     * to exit and then replaces the JAR.
     */
    public void applyUpdateAndExit(Path newJarPath) {
        try {
            // Get the current running JAR path
            org.springframework.boot.system.ApplicationHome home = new org.springframework.boot.system.ApplicationHome(
                    getClass());
            File currentJar = home.getSource();

            if (currentJar == null || !currentJar.getName().endsWith(".jar")) {
                log.error(LOG_PREFIX + "Could not identify current JAR location. Update aborted.");
                return;
            }

            Path currentJarPath = currentJar.toPath();
            log.info(LOG_PREFIX + "Applying update. Current JAR: " + currentJarPath + ", New JAR: " + newJarPath);

            // Create a batch script for the replacement
            Path scriptPath = Path.of(System.getProperty("java.io.tmpdir"), "update-script.bat");

            StringBuilder script = new StringBuilder();
            script.append("@echo off\n");
            script.append("echo Waiting for application to exit...\n");
            script.append("timeout /t 2 /nobreak > nul\n");
            script.append("echo Replacing application files...\n");
            script.append("del /f /q \"").append(currentJarPath.toAbsolutePath()).append("\"\n");
            script.append("move /y \"").append(newJarPath.toAbsolutePath()).append("\" \"")
                    .append(currentJarPath.toAbsolutePath()).append("\"\n");
            script.append("echo Update complete! Please reopen the application.\n");
            script.append("pause\n"); // Keep window open so user sees completion
            script.append("del \"%~f0\"\n"); // Delete this script itself

            Files.writeString(scriptPath, script.toString());

            log.info(LOG_PREFIX + "Executing update script: " + scriptPath);

            // Execute the script
            new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/c", scriptPath.toString())
                    .start();

            // Exit the current application
            log.info(LOG_PREFIX + "Shutting down for update...");
            System.exit(0);

        } catch (Exception e) {
            log.error(LOG_PREFIX + "Failed to apply update: " + e.getMessage(), e);
        }
    }
}

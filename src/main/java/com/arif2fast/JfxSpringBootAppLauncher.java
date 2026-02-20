package com.arif2fast;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import javafx.scene.image.Image;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

@SpringBootApplication
@Slf4j
public class JfxSpringBootAppLauncher {

    public static final String LOG_PREFIX = "JavaFx Spring Boot lifecycle: ";
    public static final String SPLASHSCREEN_CLASS_PATH = "com.arif2fast.SplashScreenPreloader";

    public static void main(String[] args) {
        log.info(LOG_PREFIX + "Starting application via main method...");

        log.info(LOG_PREFIX
                + "Set full qualified class name as system property for defining the splash screen preloader.");
        System.setProperty("javafx.preloader", SPLASHSCREEN_CLASS_PATH);

        log.info(LOG_PREFIX + "Launching the JavaFx application.");
        Application.launch(JfxSpringBootApp.class, args);
    }

    public static class JfxSpringBootApp extends Application {

        private static ConfigurableApplicationContext springApplicationContext;

        @Override
        public void init() {
            log.info(LOG_PREFIX + "Executing overridden 'init()' of JavaFx Application...");

            Set<File> searchDirs = collectSearchDirectories();
            List<String> propertyLocations = findPropertyFiles(searchDirs);
            String additionalLocations = buildAdditionalLocations(propertyLocations);

            initializeSpringContext(additionalLocations);
        }

        private Set<File> collectSearchDirectories() {
            Set<File> searchDirs = new HashSet<>();

            File cwd = new File(".").getAbsoluteFile();
            searchDirs.add(cwd);
            log.info(LOG_PREFIX + "Search Directory (CWD): " + cwd.getAbsolutePath());

            addJarDirectory(searchDirs);

            return searchDirs;
        }

        private void addJarDirectory(Set<File> searchDirs) {
            try {
                File jarDir = new org.springframework.boot.system.ApplicationHome(JfxSpringBootAppLauncher.class).getDir();
                if (jarDir != null && jarDir.exists()) {
                    searchDirs.add(jarDir.getAbsoluteFile());
                    log.info(LOG_PREFIX + "Search Directory (Home): " + jarDir.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn(LOG_PREFIX + "Failed to resolve Home directory: " + e.getMessage());
            }
        }

        private List<String> findPropertyFiles(Set<File> searchDirs) {
            List<String> propertyLocations = new ArrayList<>();

            for (File dir : searchDirs) {
                collectPropertyFilesFromDirectory(dir, propertyLocations);
            }

            return propertyLocations;
        }

        private void collectPropertyFilesFromDirectory(File dir, List<String> propertyLocations) {
            if (!dir.exists() || !dir.isDirectory()) {
                return;
            }

            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".properties"));
            if (files != null) {
                for (File f : files) {
                    propertyLocations.add("optional:file:" + f.getAbsolutePath());
                    log.info(LOG_PREFIX + "Found external property file: " + f.getAbsolutePath());
                }
            }
        }

        private String buildAdditionalLocations(List<String> propertyLocations) {
            String additionalLocations = String.join(",", propertyLocations);

            if (additionalLocations.isEmpty()) {
                log.info(LOG_PREFIX + "No external .properties files found in scanned directories.");
            } else {
                log.info(LOG_PREFIX + "Configuring additional property locations: " + additionalLocations);
            }

            return additionalLocations;
        }

        private void initializeSpringContext(String additionalLocations) {
            springApplicationContext = new SpringApplicationBuilder()
                    .sources(JfxSpringBootAppLauncher.class)
                    .properties("spring.config.additional-location=" + additionalLocations)
                    .initializers((ApplicationContextInitializer<GenericApplicationContext>) applicationContext -> {
                        applicationContext.registerBean(Application.class, () -> this);
                        applicationContext.registerBean(Parameters.class, this::getParameters);
                        applicationContext.registerBean(HostServices.class, this::getHostServices);
                    })
                    .run(getParameters().getRaw().toArray(new String[0]));
        }

        @Override
        public void start(Stage primaryStage) {
            log.info(LOG_PREFIX + "Executing overridden 'start()' of JavaFx Application...");
            log.info(LOG_PREFIX + "Publishing event 'JfxApplicationStartEvent'.");
            springApplicationContext.publishEvent(new JfxApplicationStartEvent(primaryStage));
        }

        @Override
        public void stop() {
            log.info(LOG_PREFIX + "Executing overridden 'stop()' of JavaFx Application...");
            springApplicationContext.close();
            Platform.exit();
            System.exit(0);
        }

    }

    @Component
    public static class JfxApplicationStartEventListener implements ApplicationListener<JfxApplicationStartEvent> {

        @Value("${spring.application.name}")
        private final String applicationTitle;
        private final ApplicationContext springApplicationContext;
        private final com.arif2fast.services.UpdateService updateService;

        private String applicationVersion = "Unknown";

        public JfxApplicationStartEventListener(
                @Value("${spring.application.name}") String applicationTitle,
                ApplicationContext springApplicationContext,
                com.arif2fast.services.UpdateService updateService) {

            this.applicationTitle = applicationTitle;
            this.springApplicationContext = springApplicationContext;
            this.updateService = updateService;
            loadVersion();
        }

        private void loadVersion() {
            try (java.io.InputStream is = getClass().getResourceAsStream("/version.properties")) {
                if (is != null) {
                    java.util.Properties props = new java.util.Properties();
                    props.load(is);
                    this.applicationVersion = props.getProperty("version", "Unknown");
                }
            } catch (Exception e) {
                log.warn("Could not load version.properties", e);
            }
        }

        @Override
        public void onApplicationEvent(JfxApplicationStartEvent event) {
            try {
                log.info(LOG_PREFIX + "Computing 'JfxApplicationStartEvent'...");
                Stage stage = event.getStage();
                initializePrimaryStage(stage);
                stage.show();
                log.info(LOG_PREFIX + "JavaFx Spring boot application started.");
                checkForUpdates(stage);
            } catch (Exception e) {
                log.error("Failed to load FXML or start application", e);
                throw new RuntimeException(e);
            }
            closeSplashScreen();
        }

        private void initializePrimaryStage(Stage stage) throws IOException {
            FXMLLoader fxmlLoader = new FXMLLoader(
                    getClass().getResource("/com/arif2fast/views/file-manager.fxml"));
            fxmlLoader.setControllerFactory(springApplicationContext::getBean);
            Parent root = fxmlLoader.load();
            Scene scene = new Scene(root, 600, 850);
            stage.setScene(scene);
            stage.setTitle(this.applicationTitle + " v" + this.applicationVersion);
            loadApplicationIcon(stage);
            stage.setResizable(false);
        }

        private void loadApplicationIcon(Stage stage) {
            try {
                stage.getIcons().add(new Image(getClass().getResourceAsStream("/assets/icon.png")));
            } catch (Exception e) {
                log.warn("Could not load application icon: " + e.getMessage());
            }
        }

        private void closeSplashScreen() {
            if (SplashScreenPreloader.stage != null) {
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.seconds(4));
                pause.setOnFinished(e -> {
                    log.info(LOG_PREFIX + "Closing splash screen.");
                    SplashScreenPreloader.stage.close();
                });
                pause.play();
            }
        }

        /**
         * Check for updates in background and show dialog if available.
         */
        private void checkForUpdates(Stage stage) {
            updateService.setCurrentVersion(this.applicationVersion);
            updateService.checkForUpdateAsync().thenAccept(updateInfoOpt -> {
                if (updateInfoOpt.isPresent()) {
                    Platform.runLater(() -> {
                        log.info(LOG_PREFIX + "Showing update dialog for version: " +
                                updateInfoOpt.get().getVersion());
                        com.arif2fast.views.UpdateDialog dialog = new com.arif2fast.views.UpdateDialog(
                                stage, updateService, updateInfoOpt.get(), this.applicationVersion);
                        dialog.show();
                    });
                }
            }).exceptionally(ex -> {
                log.warn(LOG_PREFIX + "Update check failed: " + ex.getMessage());
                return null;
            });
        }

    }

    public static class JfxApplicationStartEvent extends ApplicationEvent {
        public Stage getStage() {
            return (Stage) getSource();
        }

        public JfxApplicationStartEvent(Stage source) {
            super(source);
        }
    }

}

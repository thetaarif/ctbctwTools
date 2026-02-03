package com.arif2fast;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;

@SpringBootApplication
@Slf4j
public class JfxSpringBootAppLauncher {

    public static final String LOG_PREFIX = "JavaFx Spring Boot lifecycle: ";
    public static final String SPLASHSCREEN_CLASS_PATH = "com.arif2fast.SplashScreenPreloader";

    public static void main(String[] args) {
        log.info(LOG_PREFIX + "Starting application via main method...");

        log.info(LOG_PREFIX
                + "Set full qualified class name as system property for defining the splash screen preloader.");
        // System.setProperty("javafx.preloader", SPLASHSCREEN_CLASS_PATH);

        log.info(LOG_PREFIX + "Launching the JavaFx application.");
        Application.launch(JfxSpringBootApp.class, args);
    }

    public static class JfxSpringBootApp extends Application {

        private static ConfigurableApplicationContext springApplicationContext;

        @Override
        public void init() {
            log.info(LOG_PREFIX + "Executing overridden 'init()' of JavaFx Application...");

            java.util.Set<java.io.File> searchDirs = new java.util.HashSet<>();

            // 1. Current Working Directory
            java.io.File cwd = new java.io.File(".");
            searchDirs.add(cwd.getAbsoluteFile());
            log.info(LOG_PREFIX + "Search Directory (CWD): " + cwd.getAbsolutePath());

            // 2. JAR Directory (if running from JAR)
            try {
                org.springframework.boot.system.ApplicationHome home = new org.springframework.boot.system.ApplicationHome(
                        JfxSpringBootAppLauncher.class);
                java.io.File jarDir = home.getDir();
                if (jarDir != null && jarDir.exists()) {
                    searchDirs.add(jarDir.getAbsoluteFile());
                    log.info(LOG_PREFIX + "Search Directory (Home): " + jarDir.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn(LOG_PREFIX + "Failed to resolve Home directory: " + e.getMessage());
            }

            java.util.List<String> propertyLocations = new java.util.ArrayList<>();

            for (java.io.File dir : searchDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".properties"));
                    if (files != null) {
                        for (java.io.File f : files) {
                            String location = "optional:file:" + f.getAbsolutePath();
                            propertyLocations.add(location);
                            log.info(LOG_PREFIX + "Found external property file: " + f.getAbsolutePath());
                        }
                    }
                }
            }

            String additionalLocations = String.join(",", propertyLocations);

            if (additionalLocations.isEmpty()) {
                log.info(LOG_PREFIX + "No external .properties files found in scanned directories.");
            } else {
                log.info(LOG_PREFIX + "Configuring additional property locations: " + additionalLocations);
            }

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

        private String applicationVersion = "Unknown";

        public JfxApplicationStartEventListener(
                @Value("${spring.application.name}") String applicationTitle,
                ApplicationContext springApplicationContext) {

            this.applicationTitle = applicationTitle;
            this.springApplicationContext = springApplicationContext;
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
                FXMLLoader fxmlLoader = new FXMLLoader(
                        getClass().getResource("/com/arif2fast/views/file-manager.fxml"));
                fxmlLoader.setControllerFactory(springApplicationContext::getBean);
                Parent root = fxmlLoader.load();
                Scene scene = new Scene(root, 600, 850);
                Stage stage = event.getStage();
                stage.setScene(scene);
                stage.setTitle(this.applicationTitle + " v" + this.applicationVersion);
                stage.setResizable(false);
                stage.show();
                log.info(LOG_PREFIX + "JavaFx Spring boot application started.");
            } catch (Exception e) {
                log.error("Failed to load FXML or start application", e);
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            if (SplashScreenPreloader.stage != null) {
                log.info(LOG_PREFIX + "Closing splash screen.");
                SplashScreenPreloader.stage.close();
            }
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

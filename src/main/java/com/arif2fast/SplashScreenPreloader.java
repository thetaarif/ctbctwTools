package com.arif2fast;

import javafx.application.Preloader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ProgressBar;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class SplashScreenPreloader extends Preloader {

    public static Stage stage;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.initStyle(StageStyle.UNDECORATED);

        // Main container
        VBox mainBox = new VBox(20);
        mainBox.setAlignment(Pos.CENTER);
        mainBox.setStyle(
                "-fx-background-color: #1a1a1a; -fx-padding: 30; -fx-border-color: #2e7d32; -fx-border-width: 2; -fx-background-radius: 10; -fx-border-radius: 10;");

        // App Icon
        javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
        try {
            javafx.scene.image.Image icon = new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/assets/icon.png"));
            iconView.setImage(icon);
            iconView.setFitHeight(100);
            iconView.setFitWidth(100);
            iconView.setPreserveRatio(true);
        } catch (Exception e) {
            // Fallback if icon is missing
        }

        // App Title
        Label titleLabel = new Label("Liquibase Uploader");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        // Progress section
        VBox progressBox = new VBox(10);
        progressBox.setAlignment(Pos.CENTER);

        ProgressBar pb = new ProgressBar();
        pb.setPrefWidth(300);
        pb.setProgress(-1); // Indeterminate
        pb.setStyle("-fx-accent: #4caf50;");

        Label statusLabel = new Label("Initializing components...");
        statusLabel.setStyle("-fx-text-fill: #9e9e9e; -fx-font-size: 12px;");

        progressBox.getChildren().addAll(pb, statusLabel);

        // Footer Branding
        HBox footer = new HBox();
        footer.setAlignment(Pos.BOTTOM_CENTER);
        VBox.setVgrow(footer, javafx.scene.layout.Priority.ALWAYS);

        Label brandingLabel = new Label("Made by Theta Team Indonesia");
        brandingLabel.setStyle("-fx-text-fill: #4caf50; -fx-font-size: 11px; -fx-font-style: italic;");
        footer.getChildren().add(brandingLabel);

        mainBox.getChildren().addAll(iconView, titleLabel, progressBox, footer);

        StackPane root = new StackPane(mainBox);
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, 500, 350);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);

        stage.setScene(scene);
        stage.show();

        // Subtle fade-in
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(800),
                root);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();
    }

}

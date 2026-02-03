package com.arif2fast.services;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for the auto-update feature using GitHub Releases.
 * Properties are loaded from application.properties.
 */
@Component
@Getter
@Setter
public class UpdateConfig {

    /**
     * Enable or disable update checking
     */
    @Value("${update.enabled:true}")
    private boolean enabled;

    /**
     * GitHub repository owner (username or organization)
     */
    @Value("${update.github.owner:}")
    private String githubOwner;

    /**
     * GitHub repository name
     */
    @Value("${update.github.repo:}")
    private String githubRepo;

    /**
     * JAR file name pattern to look for in release assets (e.g.,
     * "ctbctwTools-*.jar")
     */
    @Value("${update.github.asset-pattern:*.jar}")
    private String assetPattern;

    /**
     * Timeout in seconds for HTTP requests
     */
    @Value("${update.timeout-seconds:5}")
    private int timeoutSeconds;

    /**
     * Directory to store downloaded updates
     */
    @Value("${update.download-dir:#{systemProperties['java.io.tmpdir']}}")
    private String downloadDir;

    /**
     * Check if GitHub configuration is valid
     */
    public boolean isGitHubConfigured() {
        return githubOwner != null && !githubOwner.isBlank()
                && githubRepo != null && !githubRepo.isBlank();
    }

    /**
     * Get the GitHub API URL for latest release
     */
    public String getGitHubApiUrl() {
        return String.format("https://api.github.com/repos/%s/%s/releases/latest",
                githubOwner, githubRepo);
    }
}

package com.arif2fast.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Model class representing version information from GitHub Releases.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateInfo {

    /**
     * Latest version available (from tag_name, e.g., "v1.0.2" or "1.0.2")
     */
    private String version;

    /**
     * Direct download URL for the JAR file (browser_download_url from assets)
     */
    private String downloadUrl;

    /**
     * Release notes / description (body from release)
     */
    private String releaseNotes;

    /**
     * Release name/title
     */
    private String releaseName;

    /**
     * File size in bytes (for progress calculation)
     */
    private long fileSize;
}

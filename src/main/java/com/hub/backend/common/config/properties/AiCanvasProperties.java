package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Limits and optional external tools used by AI Canvas.
 */
@ConfigurationProperties(prefix = "app.ai-canvas")
public class AiCanvasProperties {

    private DataSize maxAssetSize = DataSize.ofMegabytes(200);
    private DataSize userStorageQuota = DataSize.ofGigabytes(5);
    private Duration videoTimeout = Duration.ofMinutes(10);
    private Duration videoDownloadTimeout = Duration.ofSeconds(60);
    private String ffmpegCommand = "ffmpeg";
    private List<LocalModel> localModels = new ArrayList<>();

    public DataSize getMaxAssetSize() {
        return maxAssetSize;
    }

    public void setMaxAssetSize(DataSize maxAssetSize) {
        this.maxAssetSize = maxAssetSize;
    }

    public DataSize getUserStorageQuota() {
        return userStorageQuota;
    }

    public void setUserStorageQuota(DataSize userStorageQuota) {
        this.userStorageQuota = userStorageQuota;
    }

    public Duration getVideoTimeout() {
        return videoTimeout;
    }

    public void setVideoTimeout(Duration videoTimeout) {
        this.videoTimeout = videoTimeout;
    }

    public Duration getVideoDownloadTimeout() {
        return videoDownloadTimeout;
    }

    public void setVideoDownloadTimeout(Duration videoDownloadTimeout) {
        this.videoDownloadTimeout = videoDownloadTimeout;
    }

    public String getFfmpegCommand() {
        return ffmpegCommand;
    }

    public void setFfmpegCommand(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public List<LocalModel> getLocalModels() {
        return localModels;
    }

    public void setLocalModels(List<LocalModel> localModels) {
        this.localModels = localModels == null ? new ArrayList<>() : localModels;
    }

    /**
     * A locally deployed model advertised by the canvas page. Image entries can be routed through
     * the same OpenAI-compatible image client as hosted image models.
     */
    public static class LocalModel {
        private String id;
        private String name;
        private String type;
        private String description;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}

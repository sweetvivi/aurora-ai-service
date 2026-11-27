package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Attachment storage policy.
 *
 * <p>Bound from {@code app.storage.*}.</p>
 *
 * <p>Content types and extensions are both allow lists and both must match: a client can set any
 * {@code Content-Type} it likes, so the extension check is what actually stops a renamed
 * executable. Entries may be exact ({@code application/pdf}) or a prefix wildcard
 * ({@code image/*}).</p>
 */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Filesystem root used by {@link com.hub.backend.infra.storage.LocalFileStorage}. */
    private String localRoot = "./data/uploads";

    /**
     * 可公开访问的素材目录（运营上传的工具图标、案例图）。
     *
     * <p>刻意与 {@link #localRoot} 分开：附件是用户私有内容，只能经带鉴权的接口读取；
     * 而图标和案例图要直接出现在前台页面上，必须能匿名访问。放同一个目录再想靠路径区分，
     * 迟早有一次配错就把所有人的附件暴露出去。</p>
     *
     * <p>由 {@code MediaResourceConfig} 映射到 {@code /media/**}。</p>
     */
    private String publicRoot = "./data/public";

    /** Maximum accepted size of one attachment. */
    private DataSize maxFileSize = DataSize.ofMegabytes(100);

    /**
     * 单个账号所有附件的总量上限。
     *
     * <p>单文件上限挡不住「反复上传」：出图、修图、聊天附件全都落 {@code uploaded_files}，
     * 之前没有任何总量概念，一个账号可以一直写下去，直到把磁盘写满为止。画布素材库早就有
     * 5GB 配额，附件这边是唯一没有闸门的入口。</p>
     *
     * <p>0 或负数表示不限制（测试与本地调试用）。</p>
     */
    private DataSize maxUserBytes = DataSize.ofGigabytes(2);

    /** Accepted MIME types, exact values or prefix wildcards. */
    private List<String> allowedContentTypes = List.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "image/bmp",
            "audio/mpeg",
            "audio/mp3",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/mp4",
            "audio/x-m4a",
            "audio/ogg",
            "application/ogg",
            "audio/webm",
            "video/mp4",
            "video/webm",
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /** Accepted file extensions, lower case without the dot. */
    private List<String> allowedExtensions = List.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "mp3", "wav", "m4a", "ogg", "mp4", "webm",
            "pdf", "txt", "md", "csv", "json",
            "doc", "docx", "xls", "xlsx", "pptx");

    /**
     * @return whether the MIME type is covered by {@link #allowedContentTypes}, tolerating
     *         parameters such as {@code text/plain; charset=utf-8}
     */
    public boolean isContentTypeAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.split(";")[0].trim().toLowerCase();
        return allowedContentTypes.stream().anyMatch(pattern -> matches(pattern, normalized));
    }

    public boolean isExtensionAllowed(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase());
    }

    private static boolean matches(String pattern, String contentType) {
        String normalizedPattern = pattern.trim().toLowerCase();
        if (normalizedPattern.endsWith("/*")) {
            return contentType.startsWith(normalizedPattern.substring(0, normalizedPattern.length() - 1));
        }
        return normalizedPattern.equals(contentType);
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getPublicRoot() {
        return publicRoot;
    }

    public void setPublicRoot(String publicRoot) {
        this.publicRoot = publicRoot;
    }

    public DataSize getMaxUserBytes() {
        return maxUserBytes;
    }

    public void setMaxUserBytes(DataSize maxUserBytes) {
        this.maxUserBytes = maxUserBytes;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}

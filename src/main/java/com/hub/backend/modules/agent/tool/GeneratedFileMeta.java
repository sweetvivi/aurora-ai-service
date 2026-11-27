package com.hub.backend.modules.agent.tool;

import com.hub.backend.modules.file.dto.UploadedFileResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code meta.files[]} entries the chat UI uses for download cards.
 *
 * <p>Kept separate from individual tools so markdown and spreadsheet share one shape.</p>
 */
final class GeneratedFileMeta {

    private GeneratedFileMeta() {
    }

    static Map<String, Object> of(UploadedFileResponse stored) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fileId", stored.id());
        item.put("url", stored.url());
        item.put("name", stored.originalName());
        item.put("mimeType", stored.mimeType());
        item.put("sizeBytes", stored.sizeBytes());
        return item;
    }
}

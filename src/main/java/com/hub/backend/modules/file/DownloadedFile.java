package com.hub.backend.modules.file;

import com.hub.backend.modules.file.entity.UploadedFileEntity;
import org.springframework.core.io.Resource;

/**
 * A downloadable attachment: its metadata plus a readable handle on the blob.
 */
public record DownloadedFile(UploadedFileEntity file, Resource resource) {
}

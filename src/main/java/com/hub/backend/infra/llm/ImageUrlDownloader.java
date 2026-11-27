package com.hub.backend.infra.llm;

import com.hub.backend.infra.llm.dto.DownloadedImage;

/**
 * Downloads an image returned as a hosted URL by an image gateway.
 */
public interface ImageUrlDownloader {

    DownloadedImage download(String url);
}

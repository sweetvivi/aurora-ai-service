package com.hub.backend.modules.video;

import com.hub.backend.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the image-to-video reference encoding: upstream must receive an inline data URL,
 * not a platform path that only answers with the caller's Bearer token.
 */
class VideoReferenceImageDataUrlTest {

    @Test
    void encodesOwnedImageBytesAsDataUrl() {
        byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);

        String dataUrl = VideoGenerationService.toImageDataUrl("image/png", png);

        assertEquals(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                dataUrl);
    }

    @Test
    void stripsMimeParametersAndDefaultsBlankMimeToPng() {
        byte[] bytes = {1, 2, 3};

        assertTrue(VideoGenerationService.toImageDataUrl("image/jpeg; charset=binary", bytes)
                .startsWith("data:image/jpeg;base64,"));
        assertTrue(VideoGenerationService.toImageDataUrl("  ", bytes)
                .startsWith("data:image/png;base64,"));
    }

    @Test
    void rejectsEmptyOrNonImagePayloads() {
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.toImageDataUrl("image/png", new byte[0]));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.toImageDataUrl("application/pdf", new byte[] {1}));
    }
}

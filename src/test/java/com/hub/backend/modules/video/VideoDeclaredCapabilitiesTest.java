package com.hub.backend.modules.video;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.model.dto.ModelCapabilities;
import com.hub.backend.modules.video.dto.VideoGenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoDeclaredCapabilitiesTest {

    @Test
    void allowsAnythingWhenCapabilitiesAreAbsent() {
        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "grok-imagine-video",
                null,
                "first-last-frame",
                "21:9",
                "2160p",
                30,
                2));
    }

    @Test
    void allowsAnythingWhenDeclaredListsAreEmpty() {
        ModelCapabilities empty = new ModelCapabilities(
                List.of(), List.of(), List.of(), List.of(), null, 0, false, null, null, null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "grok-imagine-video",
                empty,
                "first-last-frame",
                "21:9",
                "2160p",
                30,
                2));
    }

    @Test
    void rejectsValuesOutsideDeclaredRanges() {
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "image2video"),
                List.of("16:9", "9:16"),
                List.of("720p", "1080p"),
                List.of(5, 10), null,
                1,
                false,
                null,
                null,
                null);

        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "first-last-frame", "16:9", "720p", 5, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "text2video", "1:1", "720p", 5, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "text2video", "16:9", "480p", 5, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "text2video", "16:9", "720p", 11, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "image2video", "16:9", "720p", 5, 2));
    }

    @Test
    void treatsDeclaredDurationsAsInclusiveIntegerRange() {
        ModelCapabilities sparseAndUnordered = new ModelCapabilities(
                List.of(), List.of(), List.of(), List.of(10, 5), null, 0, false, null, null, null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", sparseAndUnordered, "text2video", null, null, 7, 0));
        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", sparseAndUnordered, "text2video", null, null, 5, 0));
        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", sparseAndUnordered, "text2video", null, null, 10, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", sparseAndUnordered, "text2video", null, null, 4, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", sparseAndUnordered, "text2video", null, null, 11, 0));
    }

    @Test
    void singleDeclaredDurationRemainsFixed() {
        ModelCapabilities fixedDuration = new ModelCapabilities(
                List.of(), List.of(), List.of(), List.of(8), null, 0, false, null, null, null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", fixedDuration, "text2video", null, null, 8, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", fixedDuration, "text2video", null, null, 7, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", fixedDuration, "text2video", null, null, 9, 0));
    }

    @Test
    void rangeDeclarationsAcceptEverySecondInBetween() {
        // 上游对 4–15 这类模型是真的逐秒都收（实测 mini-OvO 的 6/8/9/11/13 秒均 200），
        // 所以区间不能再只暴露 5/10/15 三个档位。
        ModelCapabilities range = new ModelCapabilities(
                List.of("text2video"), List.of(), List.of(),
                List.of(4, 15), ModelCapabilities.DURATION_MODE_RANGE, 0, false, null, null, null);

        assertTrue(range.allowsDuration(4));
        assertTrue(range.allowsDuration(7));
        assertTrue(range.allowsDuration(15));
        assertFalse(range.allowsDuration(3));
        assertFalse(range.allowsDuration(16));

        List<Integer> options = range.durationOptions();
        assertEquals(12, options.size());
        assertEquals(4, options.get(0));
        assertEquals(15, options.get(options.size() - 1));
    }

    @Test
    void setDeclarationsRejectTheSecondsInBetween() {
        // fast-GT / mini-GT / minimax 这类只做 10 或 15 秒，没有 durationMode 时会被当成区间放行。
        ModelCapabilities set = new ModelCapabilities(
                List.of("text2video"), List.of(), List.of(),
                List.of(10, 15), ModelCapabilities.DURATION_MODE_SET, 0, false, null, null, null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "seedance 2.0 fast-GT", set, "text2video", null, null, 10, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "seedance 2.0 fast-GT", set, "text2video", null, null, 12, 0));
        assertEquals(List.of(10, 15), set.durationOptions());
    }

    @Test
    void fixedDurationIsRejectedBySetModeToo() {
        ModelCapabilities only15 = new ModelCapabilities(
                List.of("text2video"), List.of(), List.of(),
                List.of(15), ModelCapabilities.DURATION_MODE_SET, 0, false, null, null, null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "minimax H3", only15, "text2video", null, null, 15, 0));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "minimax H3", only15, "text2video", null, null, 10, 0));
    }

    @Test
    void acceptsValuesInsideDeclaredRanges() {
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "image2video", "first-last-frame"),
                List.of("16:9", "9:16"),
                List.of("720p", "1080p"),
                List.of(5, 10), null,
                2,
                true,
                null,
                null,
                null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", caps, "first-last-frame", "9:16", "1080p", 10, 2));
    }

    @Test
    void acceptsReference2videoWithinMaxImages() {
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "reference2video"),
                List.of("16:9"),
                List.of("720p"),
                List.of(5), null,
                4,
                false,
                null,
                null,
                null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "grok-imagine-video", caps, "reference2video", "16:9", "720p", 5, 3));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "grok-imagine-video", caps, "reference2video", "16:9", "720p", 5, 5));
    }

    @Test
    void memberSelectionSkipsTheStrictestMember() {
        // seedance-2.0 组里最便宜的是 fast-GT（只做 10/15 秒），但组对外开放 4–15。
        // 选路必须让 7 秒落到能做的成员上，否则页面能点、提交就 400。
        ModelCapabilities fastGt = new ModelCapabilities(
                List.of("text2video"), List.of("16:9"), List.of("480p", "720p"),
                List.of(10, 15), ModelCapabilities.DURATION_MODE_SET, 0, false, null, null, null);
        ModelCapabilities miniOvo = new ModelCapabilities(
                List.of("text2video"), List.of("16:9"), List.of("480p", "720p"),
                List.of(4, 15), ModelCapabilities.DURATION_MODE_RANGE, 0, false, null, null, null);

        assertFalse(VideoGenerationService.accepts(fastGt, request7s(), 0));
        assertTrue(VideoGenerationService.accepts(miniOvo, request7s(), 0));
    }

    private static VideoGenerationRequest request7s() {
        return new VideoGenerationRequest(
                "prompt", "seedance-2.0", "16:9", "720p", 7, null, null, List.of(), "text2video", null);
    }

    @Test
    void requireModeInputsKeepsReferenceAndFirstFrameDistinct() {
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.requireModeInputs(
                        "reference2video",
                        request("reference2video", 1L, null, List.of())));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.requireModeInputs(
                        "reference2video",
                        request("reference2video", 1L, null, List.of(2L))));
        assertDoesNotThrow(() -> VideoGenerationService.requireModeInputs(
                "reference2video",
                request("reference2video", null, null, List.of(2L, 3L))));
        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.requireModeInputs(
                        "image2video",
                        request("image2video", null, null, List.of(2L))));
        assertDoesNotThrow(() -> VideoGenerationService.requireModeInputs(
                "image2video",
                request("image2video", 1L, null, List.of())));
    }

    private static VideoGenerationRequest request(String mode,
                                                  Long referenceFileId,
                                                  Long lastFrameFileId,
                                                  List<Long> referenceFileIds) {
        return new VideoGenerationRequest(
                "prompt",
                "demo-video",
                "16:9",
                "720p",
                5,
                referenceFileId,
                lastFrameFileId,
                referenceFileIds,
                mode,
                null);
    }

    @Test
    void countsReferenceImagesAgainstTheDeclaredMaximum() {
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "image2video", "reference2video"),
                List.of("16:9"),
                List.of("720p"),
                List.of(5), null,
                2,
                false,
                null,
                null,
                null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "demo-video", caps, "reference2video", "16:9", "720p", 5, 2));

        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "reference2video", "16:9", "720p", 5, 3));
    }

    @Test
    void acceptsFourteenReferenceImagesWhenTheModelDeclaresThemLimit() {
        // xAI reference_to_video 的上限：连十几张参考图是正常用法，别再退回 2 张
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "image2video", "first-last-frame", "reference2video"),
                List.of("16:9"),
                List.of("720p", "1080p"),
                List.of(5, 8), null,
                14,
                true,
                null,
                null,
                null);

        assertDoesNotThrow(() -> VideoGenerationService.validateDeclaredCapabilities(
                "grok-imagine-video-1.5", caps, "reference2video", "16:9", "720p", 8, 14));

        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "grok-imagine-video-1.5", caps, "reference2video", "16:9", "720p", 8, 15));
    }

    @Test
    void rejectsReferenceModeWhenTheModelDidNotDeclareIt() {
        ModelCapabilities caps = new ModelCapabilities(
                List.of("text2video", "image2video", "first-last-frame"),
                List.of("16:9"),
                List.of("720p"),
                List.of(5), null,
                2,
                false,
                null,
                null,
                null);

        assertThrows(InvalidRequestException.class,
                () -> VideoGenerationService.validateDeclaredCapabilities(
                        "demo-video", caps, "reference2video", "16:9", "720p", 5, 2));
    }
}

package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiCanvasGenerationService#imageSize} 的档位覆盖。
 *
 * <p>曾经这里的 switch 只认 5 个比例，而绘画页 {@code ratioOptions} 给了 13 个：
 * 无参考图走 {@code size=1344x576} 正常，一带参考图走 {@code aspectRatio=21:9} 就 400。
 * 这些用例锁住「前端能选到的比例，后端都得能出尺寸」。</p>
 */
class AiCanvasImageSizeTest {

    @Test
    void keepsHistoricalSizesUnchanged() {
        assertEquals("1024x1024", AiCanvasGenerationService.imageSize("1:1", "1K"));
        assertEquals("1536x864", AiCanvasGenerationService.imageSize("16:9", "1K"));
        assertEquals("864x1536", AiCanvasGenerationService.imageSize("9:16", "1K"));
        assertEquals("1152x864", AiCanvasGenerationService.imageSize("4:3", "1K"));
        assertEquals("864x1152", AiCanvasGenerationService.imageSize("3:4", "1K"));
    }

    @Test
    void mapsPaintingRatioOptionsToTheirOneKBase() {
        assertEquals("1344x576", AiCanvasGenerationService.imageSize("21:9", "1K"));
        assertEquals("1344x672", AiCanvasGenerationService.imageSize("2:1", "1K"));
        assertEquals("1280x832", AiCanvasGenerationService.imageSize("3:2", "1K"));
        assertEquals("1120x896", AiCanvasGenerationService.imageSize("5:4", "1K"));
        assertEquals("896x1120", AiCanvasGenerationService.imageSize("4:5", "1K"));
        assertEquals("832x1280", AiCanvasGenerationService.imageSize("2:3", "1K"));
        assertEquals("672x1344", AiCanvasGenerationService.imageSize("1:2", "1K"));
    }

    /** 绘画页 13 个档位一个都不能再落到抛出分支上。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "1:1", "21:9", "2:1", "16:9", "3:2", "4:3", "5:4",
            "4:5", "3:4", "2:3", "9:16", "1:2"})
    void everyRatioOptionProducesASize(String ratio) {
        String size = AiCanvasGenerationService.imageSize(ratio, "1K");

        String[] parts = size.split("x");
        assertEquals(2, parts.length, "尺寸应形如 宽x高，实际 " + size);
        assertTrue(Integer.parseInt(parts[0]) > 0);
        assertTrue(Integer.parseInt(parts[1]) > 0);
    }

    @Test
    void scalesWithResolution() {
        assertEquals("2688x1152", AiCanvasGenerationService.imageSize("21:9", "2K"));
        assertEquals("4096x4096", AiCanvasGenerationService.imageSize("1:1", "4K"));
    }

    @Test
    void passesAutoThrough() {
        assertNull(AiCanvasGenerationService.imageSize("auto", "1K"));
        assertNull(AiCanvasGenerationService.imageSize("Auto", "1K"));
    }

    /** 没给比例时按 1:1 处理，这是改动前的既有行为，不能被顺手改掉。 */
    @Test
    void fallsBackToOneToOneWhenRatioIsMissing() {
        assertEquals("1024x1024", AiCanvasGenerationService.imageSize(null, "1K"));
        assertEquals("1024x1024", AiCanvasGenerationService.imageSize("  ", "1K"));
    }

    @Test
    void passesExplicitPixelSizeThrough() {
        assertEquals("1344x576", AiCanvasGenerationService.imageSize("1344x576", "1K"));
    }

    /** 前端以后加比例不用回头改后端：未列出的 a:b 现算，宽高对齐 8 的倍数。 */
    @Test
    void derivesUnlistedRatiosAndAlignsToEight() {
        String size = AiCanvasGenerationService.imageSize("7:5", "1K");

        String[] parts = size.split("x");
        int width = Integer.parseInt(parts[0]);
        int height = Integer.parseInt(parts[1]);
        assertEquals(0, width % 8, "宽应对齐 8 的倍数，实际 " + width);
        assertEquals(0, height % 8, "高应对齐 8 的倍数，实际 " + height);
        assertEquals(1.4, (double) width / height, 0.02);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wide", "16:0", "0:9", "abc:def"})
    void rejectsUnparsableRatios(String ratio) {
        assertThrows(InvalidRequestException.class,
                () -> AiCanvasGenerationService.imageSize(ratio, "1K"));
    }
}

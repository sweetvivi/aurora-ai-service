package com.hub.backend.modules.smartedit;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskedImageCompositorTest {

    private final MaskedImageCompositor compositor = new MaskedImageCompositor();

    @Test
    void keepsEveryPixelOutsideTheTransparentMaskFromTheSourceImage() throws IOException {
        BufferedImage source = solidImage(21, 21, Color.GREEN);
        BufferedImage mask = solidImage(21, 21, Color.WHITE);
        transparentRect(mask, 6, 6, 9, 9);
        BufferedImage generated = solidImage(21, 21, Color.RED);

        byte[] composedBytes = compositor.composite(
                png(source), png(mask), png(generated));
        BufferedImage composed = ImageIO.read(new ByteArrayInputStream(composedBytes));

        assertEquals(21, composed.getWidth());
        assertEquals(21, composed.getHeight());
        for (int y = 0; y < composed.getHeight(); y++) {
            for (int x = 0; x < composed.getWidth(); x++) {
                if (x >= 6 && x < 15 && y >= 6 && y < 15) {
                    continue;
                }
                assertEquals(Color.GREEN.getRGB(), composed.getRGB(x, y),
                        "protected pixel at " + x + "," + y);
            }
        }
        assertEquals(Color.RED.getRGB(), composed.getRGB(10, 10));
    }

    @Test
    void feathersTheMaskBoundaryInwardWithoutSpillingOutside() throws IOException {
        BufferedImage source = solidImage(21, 21, Color.BLUE);
        BufferedImage mask = solidImage(21, 21, Color.WHITE);
        transparentRect(mask, 6, 6, 9, 9);
        BufferedImage generated = solidImage(21, 21, Color.RED);

        BufferedImage composed = ImageIO.read(new ByteArrayInputStream(
                compositor.composite(png(source), png(mask), png(generated))));

        Color edge = new Color(composed.getRGB(6, 10), true);
        Color feathered = new Color(composed.getRGB(7, 10), true);
        Color center = new Color(composed.getRGB(10, 10), true);
        assertEquals(Color.BLUE.getRGB(), edge.getRGB(),
                "the first editable pixel must keep the source color");
        assertTrue(feathered.getRed() > 0 && feathered.getBlue() > 0,
                "feathering should start one pixel inside the editable area");
        assertEquals(Color.RED.getRGB(), center.getRGB());
        assertEquals(Color.BLUE.getRGB(), composed.getRGB(5, 10),
                "the pixel just outside the mask must remain unchanged");
    }

    @Test
    void usesAWiderTransitionWhenTheEditableMaskReachesTheCanvasEdge() throws IOException {
        BufferedImage source = solidImage(200, 200, Color.BLUE);
        BufferedImage mask = solidImage(200, 200, Color.WHITE);
        transparentBorder(mask, 32);
        BufferedImage generated = solidImage(200, 200, Color.RED);

        BufferedImage composed = ImageIO.read(new ByteArrayInputStream(
                compositor.composite(png(source), png(mask), png(generated))));

        Color nearBoundary = new Color(composed.getRGB(22, 100), true);
        Color outerExtension = new Color(composed.getRGB(2, 100), true);
        assertTrue(nearBoundary.getBlue() > nearBoundary.getRed(),
                "outpainting should blend across a visibly wider transition band");
        assertTrue(outerExtension.getRed() > outerExtension.getBlue(),
                "the extended area away from the original should use generated pixels");
    }

    @Test
    void suppressesAModelPaintedOutlineNearTheProtectedBoundary() throws IOException {
        BufferedImage source = solidImage(240, 240, Color.BLUE);
        BufferedImage mask = solidImage(240, 240, Color.WHITE);
        transparentRect(mask, 0, 0, 240, 32);
        BufferedImage generated = solidImage(240, 240, Color.RED);
        Graphics2D graphics = generated.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 27, 240, 5);
        } finally {
            graphics.dispose();
        }

        BufferedImage composed = ImageIO.read(new ByteArrayInputStream(
                compositor.composite(png(source), png(mask), png(generated))));

        for (int y = 27; y < 32; y++) {
            assertEquals(Color.BLUE.getRGB(), composed.getRGB(120, y),
                    "the source hold band must hide the generated outline at y=" + y);
        }
    }

    @Test
    void rejectsAnOpaqueMaskBeforeCallingTheModel() throws IOException {
        BufferedImage source = solidImage(2, 2, Color.GREEN);
        BufferedImage opaqueMask = solidImage(2, 2, Color.WHITE);

        assertThrows(InvalidRequestException.class,
                () -> compositor.validate(png(source), png(opaqueMask)));
    }

    @Test
    void rejectsAGeneratedCanvasWithADifferentRequestedSize() throws IOException {
        BufferedImage source = solidImage(16, 16, Color.GREEN);
        BufferedImage mask = solidImage(16, 16, Color.WHITE);
        transparentRect(mask, 6, 6, 4, 4);
        BufferedImage generated = solidImage(32, 32, Color.RED);

        assertThrows(LlmInvocationException.class, () -> compositor.composite(
                png(source),
                png(mask),
                png(generated),
                new MaskedImageCompositor.ImageDimensions(16, 16)));
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void transparentRect(BufferedImage image,
                                        int x,
                                        int y,
                                        int width,
                                        int height) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, 0);
            }
        }
    }

    private static void transparentBorder(BufferedImage image, int inset) {
        int contentWidth = image.getWidth() - inset;
        int contentHeight = image.getHeight() - inset;
        transparentRect(image, 0, 0, image.getWidth(), inset);
        transparentRect(image, 0, contentHeight, image.getWidth(), inset);
        transparentRect(image, 0, inset, inset, contentHeight - inset);
        transparentRect(image, contentWidth, inset, inset, contentHeight - inset);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}

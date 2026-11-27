package com.hub.backend.modules.smartedit;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Restores every pixel outside the editable (transparent) part of an image mask and
 * feathers the transition inward so local edits do not end with a hard cut.
 */
@Component
public class MaskedImageCompositor {

    private static final double FEATHER_RADIUS_RATIO = 0.008;
    private static final int MIN_FEATHER_RADIUS = 4;
    private static final int MAX_FEATHER_RADIUS = 18;
    private static final double OUTPAINT_FEATHER_RADIUS_RATIO = 0.075;
    private static final int MIN_OUTPAINT_FEATHER_RADIUS = 64;
    private static final int MAX_OUTPAINT_FEATHER_RADIUS = 128;
    private static final double OUTPAINT_SOURCE_HOLD_RATIO = 0.35;

    public record ImageDimensions(int width, int height) {
    }

    public ImageDimensions validate(byte[] sourceContent, byte[] maskContent) {
        BufferedImage source = readInput(sourceContent);
        BufferedImage mask = readInput(maskContent);
        requireMatchingDimensions(source, mask);
        if (!hasEditablePixels(mask)) {
            throw new InvalidRequestException("蒙版里要有一块透明的可编辑区域，请先涂抹要修改的位置");
        }
        return new ImageDimensions(source.getWidth(), source.getHeight());
    }

    public void validateGeneratedDimensions(byte[] generatedContent,
                                            ImageDimensions expectedDimensions) {
        BufferedImage generated = readGenerated(generatedContent);
        requireGeneratedDimensions(generated, expectedDimensions);
    }

    public byte[] composite(byte[] sourceContent, byte[] maskContent, byte[] generatedContent) {
        return composite(sourceContent, maskContent, generatedContent, null);
    }

    public byte[] composite(byte[] sourceContent,
                            byte[] maskContent,
                            byte[] generatedContent,
                            ImageDimensions expectedDimensions) {
        BufferedImage source = readInput(sourceContent);
        BufferedImage mask = readInput(maskContent);
        requireMatchingDimensions(source, mask);

        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage generated = readGenerated(generatedContent);
        if (expectedDimensions != null) {
            requireGeneratedDimensions(generated, expectedDimensions);
        }
        generated = resizeToCanvas(generated, width, height);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        MaskDistance maskDistance = editableMaskDistance(mask);
        boolean outpainting = hasEditableCanvasEdge(mask);
        double featherRadius = Math.min(
                outpainting
                        ? adaptiveOutpaintFeatherRadius(width, height)
                        : adaptiveFeatherRadius(width, height),
                maskDistance.maxDistance());
        double sourceHoldRadius = outpainting
                ? Math.min(
                        Math.max(1.0, featherRadius - 1.0),
                        Math.max(4.0, featherRadius * OUTPAINT_SOURCE_HOLD_RATIO))
                : 1.0;
        int[] sourceRow = new int[width];
        int[] maskRow = new int[width];
        int[] generatedRow = new int[width];
        int[] resultRow = new int[width];

        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, sourceRow, 0, width);
            mask.getRGB(0, y, width, 1, maskRow, 0, width);
            generated.getRGB(0, y, width, 1, generatedRow, 0, width);
            for (int x = 0; x < width; x++) {
                int maskAlpha = maskRow[x] >>> 24;
                int index = y * width + x;
                int distance = maskDistance.distances()[index];
                if (maskAlpha == 255 || distance == 0) {
                    resultRow[x] = sourceRow[x];
                    continue;
                }
                int generatedWeight = featherWeight(
                        maskAlpha, distance / 3.0, featherRadius, sourceHoldRadius);
                if (generatedWeight == 255) {
                    resultRow[x] = generatedRow[x];
                } else {
                    resultRow[x] = blend(
                            sourceRow[x], generatedRow[x], generatedWeight);
                }
            }
            result.setRGB(0, y, width, 1, resultRow, 0, width);
        }
        return writePng(result);
    }

    private static BufferedImage readInput(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("原图和蒙版都不能为空");
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new InvalidRequestException("原图或蒙版无法识别为图片");
            }
            return image;
        } catch (IOException exception) {
            throw new InvalidRequestException("原图或蒙版无法识别为图片");
        }
    }

    private static BufferedImage readGenerated(byte[] content) {
        if (content == null || content.length == 0) {
            throw new LlmInvocationException("图片编辑失败：模型没有返回图片，请重试");
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new LlmInvocationException("图片编辑失败：模型返回的图片无法解析，请重试");
            }
            return image;
        } catch (IOException exception) {
            throw new LlmInvocationException(
                    "图片编辑失败：模型返回的图片无法解析，请重试", exception);
        }
    }

    private static void requireGeneratedDimensions(BufferedImage generated,
                                                   ImageDimensions expectedDimensions) {
        if (generated.getWidth() != expectedDimensions.width()
                || generated.getHeight() != expectedDimensions.height()) {
            throw new LlmInvocationException(
                    "图片编辑失败：返回尺寸 %dx%d，与请求的 %dx%d 不符，请重试"
                            .formatted(
                                    generated.getWidth(),
                                    generated.getHeight(),
                                    expectedDimensions.width(),
                                    expectedDimensions.height()));
        }
    }

    private static void requireMatchingDimensions(BufferedImage source, BufferedImage mask) {
        if (source.getWidth() != mask.getWidth() || source.getHeight() != mask.getHeight()) {
            throw new InvalidRequestException("原图和蒙版的尺寸必须一致");
        }
    }

    private static boolean hasEditablePixels(BufferedImage mask) {
        int[] row = new int[mask.getWidth()];
        for (int y = 0; y < mask.getHeight(); y++) {
            mask.getRGB(0, y, mask.getWidth(), 1, row, 0, mask.getWidth());
            for (int pixel : row) {
                if ((pixel >>> 24) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasEditableCanvasEdge(BufferedImage mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        int[] row = new int[width];
        mask.getRGB(0, 0, width, 1, row, 0, width);
        for (int pixel : row) {
            if ((pixel >>> 24) < 255) {
                return true;
            }
        }
        mask.getRGB(0, height - 1, width, 1, row, 0, width);
        for (int pixel : row) {
            if ((pixel >>> 24) < 255) {
                return true;
            }
        }
        for (int y = 0; y < height; y++) {
            if ((mask.getRGB(0, y) >>> 24) < 255
                    || (mask.getRGB(width - 1, y) >>> 24) < 255) {
                return true;
            }
        }
        return false;
    }

    /**
     * Approximate Euclidean distance from each editable pixel to the nearest protected pixel.
     * Distances use a 3:4 chamfer grid and are divided by three before blending.
     */
    private static MaskDistance editableMaskDistance(BufferedImage mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        int[] distances = new int[width * height];
        int unbounded = (width + height) * 3 + 3;
        int[] row = new int[width];

        for (int y = 0; y < height; y++) {
            mask.getRGB(0, y, width, 1, row, 0, width);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                distances[rowOffset + x] =
                        (row[x] >>> 24) < 255 ? unbounded : 0;
            }
        }

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int index = rowOffset + x;
                int distance = distances[index];
                if (distance == 0) {
                    continue;
                }
                if (x > 0) {
                    distance = Math.min(distance, distances[index - 1] + 3);
                }
                if (y > 0) {
                    distance = Math.min(distance, distances[index - width] + 3);
                    if (x > 0) {
                        distance = Math.min(
                                distance, distances[index - width - 1] + 4);
                    }
                    if (x + 1 < width) {
                        distance = Math.min(
                                distance, distances[index - width + 1] + 4);
                    }
                }
                distances[index] = distance;
            }
        }

        int maxDistance = 0;
        for (int y = height - 1; y >= 0; y--) {
            int rowOffset = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int index = rowOffset + x;
                int distance = distances[index];
                if (distance == 0) {
                    continue;
                }
                if (x + 1 < width) {
                    distance = Math.min(distance, distances[index + 1] + 3);
                }
                if (y + 1 < height) {
                    distance = Math.min(distance, distances[index + width] + 3);
                    if (x + 1 < width) {
                        distance = Math.min(
                                distance, distances[index + width + 1] + 4);
                    }
                    if (x > 0) {
                        distance = Math.min(
                                distance, distances[index + width - 1] + 4);
                    }
                }
                distances[index] = distance;
                maxDistance = Math.max(maxDistance, distance);
            }
        }
        return new MaskDistance(distances, Math.max(1.0, maxDistance / 3.0));
    }

    private static int adaptiveFeatherRadius(int width, int height) {
        int scaled = (int) Math.round(Math.min(width, height) * FEATHER_RADIUS_RATIO);
        return Math.max(MIN_FEATHER_RADIUS, Math.min(MAX_FEATHER_RADIUS, scaled));
    }

    private static int adaptiveOutpaintFeatherRadius(int width, int height) {
        int scaled = (int) Math.round(
                Math.min(width, height) * OUTPAINT_FEATHER_RADIUS_RATIO);
        return Math.max(
                MIN_OUTPAINT_FEATHER_RADIUS,
                Math.min(MAX_OUTPAINT_FEATHER_RADIUS, scaled));
    }

    private static int featherWeight(int maskAlpha,
                                     double distance,
                                     double featherRadius,
                                     double sourceHoldRadius) {
        double alphaWeight = (255 - maskAlpha) / 255.0;
        double progress = featherRadius <= 1.0
                ? 1.0
                : clamp(
                        (distance - sourceHoldRadius)
                                / (featherRadius - sourceHoldRadius));
        double smoothProgress = progress * progress * (3.0 - 2.0 * progress);
        return (int) Math.round(alphaWeight * smoothProgress * 255.0);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static BufferedImage resizeToCanvas(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static int blend(int source, int generated, int generatedWeight) {
        int sourceWeight = 255 - generatedWeight;
        int alpha = channel(source, generated, sourceWeight, generatedWeight, 24);
        int red = channel(source, generated, sourceWeight, generatedWeight, 16);
        int green = channel(source, generated, sourceWeight, generatedWeight, 8);
        int blue = channel(source, generated, sourceWeight, generatedWeight, 0);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int channel(int source, int generated, int sourceWeight,
                               int generatedWeight, int shift) {
        int sourceChannel = source >>> shift & 0xFF;
        int generatedChannel = generated >>> shift & 0xFF;
        return (sourceChannel * sourceWeight + generatedChannel * generatedWeight + 127) / 255;
    }

    private static byte[] writePng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new LlmInvocationException("图片处理失败：结果图无法编码，请重试");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new LlmInvocationException("图片处理失败：结果图无法编码，请重试", exception);
        }
    }

    private record MaskDistance(int[] distances, double maxDistance) {
    }
}

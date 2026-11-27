package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.config.properties.AiCanvasProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.StorageException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Optional FFmpeg backed video trimming.
 */
@Component
public class AiCanvasFfmpegService {

    private static final int PROCESS_OUTPUT_LIMIT = 4096;

    private final AiCanvasProperties properties;

    public AiCanvasFfmpegService(AiCanvasProperties properties) {
        this.properties = properties;
    }

    public byte[] trim(byte[] input, String inputExtension, double startTime, double endTime) {
        if (input == null || input.length == 0) {
            throw new InvalidRequestException("videoDataUrl does not contain video data");
        }
        if (endTime <= startTime) {
            throw new InvalidRequestException("endTime must be greater than startTime");
        }

        Path directory = null;
        try {
            directory = Files.createTempDirectory("ai-canvas-video-");
            Path source = directory.resolve("source." + safeExtension(inputExtension));
            Path output = directory.resolve("trimmed.mp4");
            Files.write(source, input);

            List<String> command = new ArrayList<>();
            command.add(properties.getFfmpegCommand());
            command.add("-y");
            command.add("-ss");
            command.add(Double.toString(startTime));
            command.add("-i");
            command.add(source.toString());
            command.add("-t");
            command.add(Double.toString(endTime - startTime));
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-c:a");
            command.add("aac");
            command.add("-movflags");
            command.add("+faststart");
            command.add(output.toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] outputText = process.getInputStream().readNBytes(PROCESS_OUTPUT_LIMIT);
            boolean finished = process.waitFor(
                    properties.getVideoTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceUnavailableException("Video trimming timed out");
            }
            if (process.exitValue() != 0) {
                throw new InvalidRequestException(
                        "FFmpeg could not trim this video: " + outputSnippet(outputText));
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new InvalidRequestException("Video trimming produced no output");
            }
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new ServiceUnavailableException(
                    "FFmpeg is not available; configure app.ai-canvas.ffmpeg-command");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Video trimming was interrupted");
        } finally {
            deleteDirectoryQuietly(directory);
        }
    }

    private static String safeExtension(String extension) {
        if (extension == null) {
            return "mp4";
        }
        String normalized = extension.strip().toLowerCase();
        return normalized.matches("[a-z0-9]{1,8}") ? normalized : "mp4";
    }

    private static String outputSnippet(byte[] output) {
        String value = new String(output, StandardCharsets.UTF_8).strip();
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(value.length() - 500);
    }

    private static void deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup.
                }
            });
        } catch (IOException exception) {
            throw new StorageException("Unable to clean temporary video files", exception);
        }
    }
}

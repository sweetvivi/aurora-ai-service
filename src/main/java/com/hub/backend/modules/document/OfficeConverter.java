package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Drives a headless LibreOffice to convert office documents.
 *
 * <p>This is the piece that makes 「Word → PDF」「Word → 图片」「Excel → 图片」「PDF → Word」 honest
 * results instead of re-flowed text. LibreOffice is the only renderer that behaves the same on a
 * server: no desktop session, no visible window, no COM apartment. The Word COM route would give
 * slightly better fidelity on a Windows box but it launches WINWORD.EXE per request, and a single
 * stuck instance takes the whole endpoint down with it.</p>
 *
 * <p>Three properties matter here and are all deliberate:</p>
 * <ul>
 *   <li><b>One profile per call.</b> LibreOffice refuses to run two instances against the same user
 *       profile — the second one silently attaches to the first and ignores its arguments, which
 *       looks exactly like "conversion produced no file". Each call therefore gets its own
 *       throwaway {@code -env:UserInstallation} directory.</li>
 *   <li><b>Bounded concurrency.</b> Every conversion is a full office process (100 MB+ resident).
 *       A semaphore keeps a burst of clicks from fork-bombing the host.</li>
 *   <li><b>Hard timeout with kill.</b> A malformed file can wedge the converter; the process is
 *       destroyed rather than left to accumulate.</li>
 * </ul>
 */
@Component
public class OfficeConverter {

    private static final Logger log = LoggerFactory.getLogger(OfficeConverter.class);

    /** Candidate locations, checked in order. The first existing one wins. */
    private static final List<String> DEFAULT_CANDIDATES = List.of(
            // Relative to the repo root, which is where run-local.sh's parent sits.
            "../tools/libreoffice/program/soffice.exe",
            "../tools/libreoffice/program/soffice",
            "tools/libreoffice/program/soffice.exe",
            "tools/libreoffice/program/soffice",
            "C:/Program Files/LibreOffice/program/soffice.exe",
            "C:/Program Files (x86)/LibreOffice/program/soffice.exe",
            "/usr/bin/soffice",
            "/usr/lib/libreoffice/program/soffice",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice");

    /**
     * Bare names looked up on PATH. Container images (e.g. {@code apt-get install libreoffice})
     * and most distro packages do not install at a fixed path, so the candidate list above is not
     * enough on a server.
     */
    private static final List<String> PATH_NAMES = List.of("soffice", "libreoffice");

    private final String configuredPath;
    private final Duration budget;
    private final Semaphore permits;
    private volatile Path resolved;

    public OfficeConverter(
            @Value("${app.document.libreoffice-path:}") String configuredPath,
            @Value("${app.document.convert-timeout-seconds:120}") int timeoutSeconds,
            @Value("${app.document.convert-parallelism:2}") int parallelism) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.strip();
        this.budget = Duration.ofSeconds(Math.max(15, timeoutSeconds));
        this.permits = new Semaphore(Math.max(1, parallelism), true);
    }

    /** True when a usable LibreOffice binary was found; the page greys out the tools otherwise. */
    public boolean available() {
        return binary().isPresent();
    }

    /** Path of the binary in use, for the capabilities payload and the startup log. */
    public String binaryPath() {
        return binary().map(Path::toString).orElse("");
    }

    /**
     * Converts one file and returns the produced file.
     *
     * @param source      file name with a real extension — the converter keys its output filter off it
     * @param bytes       the input document
     * @param target      one of {@code pdf}, {@code docx}, {@code png}, {@code jpg}
     * @param sourceFormat the document family ({@code Word} / {@code Excel} / {@code PDF}); the
     *                    image filter differs per family, so it cannot be guessed from the output
     */
    public Converted convert(String source, byte[] bytes, String target, String sourceFormat) {
        Path soffice = binary().orElseThrow(() -> new ServiceUnavailableException(
                "服务端没有可用的文档渲染引擎，格式转换暂不可用"));

        String inputName = safeName(source);
        String base = baseName(inputName);
        String extension = target.toLowerCase(Locale.ROOT);

        Path work = null;
        boolean acquired = false;
        try {
            try {
                acquired = permits.tryAcquire(budget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException("转换排队被中断，请重试");
            }
            if (!acquired) {
                throw new ServiceUnavailableException("文档转换队列已满，请稍后重试");
            }

            work = Files.createTempDirectory("doc-convert-");
            Path input = work.resolve(inputName);
            Files.write(input, bytes);

            Path profile = work.resolve("profile");
            Path outDir = work.resolve("out");
            Files.createDirectories(profile);
            Files.createDirectories(outDir);

            String filter = filterFor(extension, sourceFormat);
            String importFilter = importFilterFor(extension, sourceFormat);
            run(soffice, input, outDir, profile, filter, importFilter);

            List<Path> produced = listFiles(outDir);
            if (produced.isEmpty()) {
                throw new InvalidRequestException(
                        "转换没有产出文件。文件可能已损坏，或格式与所选工具不匹配");
            }

            if (produced.size() == 1) {
                Path single = produced.get(0);
                return new Converted(base + "." + extension, extension,
                        mimeFor(extension), Files.readAllBytes(single), 1);
            }
            return new Converted(base + "-" + extension + ".zip", "zip", "application/zip",
                    zip(produced, base), produced.size());
        } catch (IOException failure) {
            throw new InvalidRequestException("转换过程出错：%s".formatted(failure.getMessage()));
        } finally {
            if (acquired) {
                permits.release();
            }
            deleteQuietly(work);
        }
    }

    /**
     * Converts an office document to PDF bytes only.
     *
     * <p>Used as the first step of image rendering: LibreOffice's own PNG export always writes
     * ~96 DPI whatever options are passed ({@code writer_png_Export} ignores PixelWidth/Height),
     * which would make a "150 DPI" label a lie. Going through PDF and rasterising with PDFBox
     * gives a real, controllable DPI and one image per page.</p>
     */
    public byte[] toPdf(String source, byte[] bytes) {
        Converted pdf = convert(source, bytes, "pdf", "");
        return pdf.bytes();
    }

    private void run(Path soffice, Path input, Path outDir, Path profile, String filter,
                     String importFilter) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(soffice.toString());
        command.add("--headless");
        command.add("--norestore");
        command.add("--nolockcheck");
        command.add("--nodefault");
        command.add("--nofirststartwizard");
        // A private profile per invocation; see the class comment.
        command.add("-env:UserInstallation=" + profile.toUri());
        if (importFilter != null && !importFilter.isBlank()) {
            command.add("--infilter=" + importFilter);
        }
        command.add("--convert-to");
        command.add(filter);
        command.add("--outdir");
        command.add(outDir.toString());
        command.add(input.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // LibreOffice is chatty on stdout/stderr even when headless. Discarding it avoids a full
        // pipe blocking the child; the exit code and the output directory are what we check.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        // Run in the scratch directory so a converter that writes a stray file next to its CWD
        // (crash dumps, .~lock files) cannot litter the application's working directory — which
        // on a server is usually a read-only or root-owned directory.
        builder.directory(input.getParent().toFile());
        // A container often has no writable HOME; without this LibreOffice tries to create
        // ~/.config and can fail before it ever reads -env:UserInstallation.
        Map<String, String> environment = builder.environment();
        environment.putIfAbsent("HOME", input.getParent().toString());
        environment.putIfAbsent("TMPDIR", input.getParent().toString());
        Process process = builder.start();

        try {
            if (!process.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("文档转换超时（{}s），已终止进程", budget.toSeconds());
                throw new ServiceUnavailableException(
                        "转换超过 %d 秒仍未完成，文件可能过大或已损坏".formatted(budget.toSeconds()));
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("文档转换进程退出码 {}：{}", exit, filter);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("转换被中断，请重试");
        }
    }

    /** LibreOffice writes output next to itself but the name can differ in case; list the directory. */
    private static List<Path> listFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private byte[] zip(List<Path> files, String base) {
        try (var out = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(file.getFileName().toString()));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
            zos.finish();
            return out.toByteArray();
        } catch (IOException failure) {
            throw new InvalidRequestException("打包转换结果失败：%s".formatted(failure.getMessage()));
        }
    }

    /**
     * LibreOffice filter names. Getting these wrong is the difference between "converted" and
     * "produced an empty file", so they are spelled out rather than guessed.
     *
     * <p>The image filter is source-family dependent: a Writer document must go through
     * {@code writer_png_Export}, a Calc sheet through {@code calc_png_Export}. Handing a Writer
     * document {@code draw_png_Export} fails with {@code SfxBaseModel::impl_store ... Error Area:Io},
     * which surfaces as an empty output directory rather than a clear error.</p>
     */
    /**
     * The import filter is what makes PDF → Word possible at all.
     *
     * <p>Without it LibreOffice opens a .pdf in Draw, and Draw has no Word export filter — the run
     * fails with {@code no export filter ... found, aborting} and an empty output directory.
     * Forcing {@code writer_pdf_import} opens the PDF as a Writer document, which does have one.
     * Every other format is detected correctly and needs no override.</p>
     */
    private static String importFilterFor(String targetExtension, String sourceFormat) {
        boolean isPdfSource = "PDF".equalsIgnoreCase(sourceFormat);
        if (isPdfSource && "docx".equals(targetExtension)) {
            return "writer_pdf_import";
        }
        return "";
    }

    private static String filterFor(String extension, String sourceFormat) {
        String family = switch (sourceFormat == null ? "" : sourceFormat.toUpperCase(Locale.ROOT)) {
            case "WORD" -> "writer";
            case "EXCEL" -> "calc";
            default -> "draw";
        };
        return switch (extension) {
            case "pdf" -> "pdf:writer_pdf_Export";
            case "docx" -> "docx:MS Word 2007 XML";
            case "png" -> "png:" + family + "_png_Export";
            case "jpg", "jpeg" -> "jpg:" + family + "_jpg_Export";
            default -> throw new InvalidRequestException("不支持的转换目标格式：" + extension);
        };
    }

    private Optional<Path> binary() {
        Path cached = resolved;
        if (cached != null) {
            return cached.toString().isEmpty() ? Optional.empty() : Optional.of(cached);
        }
        synchronized (this) {
            if (resolved != null) {
                return resolved.toString().isEmpty() ? Optional.empty() : Optional.of(resolved);
            }
            Path found = locate();
            // Cache the miss too, as a sentinel empty path, so we do not hit the filesystem per click.
            resolved = found == null ? Paths.get("") : found;
            if (found == null) {
                log.warn("未找到 LibreOffice，Word→PDF / 转图片 / PDF→Word 将不可用。"
                        + "可用 app.document.libreoffice-path 指定路径");
            } else {
                log.info("文档渲染引擎：{}", found);
            }
            return Optional.ofNullable(found);
        }
    }

    private Path locate() {
        if (!configuredPath.isEmpty()) {
            Path path = Paths.get(configuredPath);
            if (Files.isExecutable(path) || Files.isRegularFile(path)) {
                return path.toAbsolutePath();
            }
            log.warn("app.document.libreoffice-path 指向的文件不存在：{}", configuredPath);
        }
        for (String candidate : DEFAULT_CANDIDATES) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath();
            }
        }
        // Then PATH. Container images and distro packages install soffice somewhere on PATH
        // rather than at a fixed location, so a hardcoded candidate list alone would report
        // "renderer missing" on a perfectly good deployment.
        for (String executable : PATH_NAMES) {
            Path onPath = onPath(executable);
            if (onPath != null) {
                return onPath;
            }
        }
        return null;
    }

    /** Resolves a bare executable name against {@code PATH}, honouring PATHEXT on Windows. */
    private static Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String separator = File.pathSeparator;
        String pathext = System.getenv("PATHEXT");
        List<String> suffixes = new ArrayList<>();
        suffixes.add("");
        if (pathext != null && !pathext.isBlank()) {
            for (String ext : pathext.split(separator)) {
                if (!ext.isBlank()) {
                    suffixes.add(ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String dir : path.split(java.util.regex.Pattern.quote(separator))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate = Paths.get(dir, name + suffix);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String safeName(String source) {
        String name = source == null || source.isBlank() ? "document" : source;
        // Strip any directory component and characters a shell or the converter would choke on.
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        return name.isEmpty() ? "document" : name;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "文档" : base;
    }

    private static String mimeFor(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a successful conversion over.
                }
            });
        } catch (IOException ignored) {
            // Same reasoning.
        }
    }

    /** Result of one conversion: bytes plus what the caller needs to store and describe them. */
    public record Converted(String fileName, String extension, String mimeType,
                            byte[] bytes, int pageCount) {
    }
}

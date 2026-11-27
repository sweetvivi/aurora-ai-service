package com.hub.backend.infra.pptmaster.agent;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

final class AgentOutputResolver {

    private AgentOutputResolver() {
    }

    record Claimed(Path pptx, Path project, int slides) {
    }

    static Claimed resolve(Path repo, Path jobDir, String jobKey, Path outputPptx, Path resultPath, Instant started)
            throws IOException {
        Instant freshAfter = started.minusSeconds(30);

        String claimed = readResultPath(resultPath);
        if (StringUtils.hasText(claimed)) {
            Path p = Path.of(claimed);
            if (acceptClaimed(p, jobDir, jobKey, freshAfter)) {
                Path proj = projectDirOf(p, jobDir);
                try {
                    Files.copy(p, outputPptx, StandardCopyOption.REPLACE_EXISTING);
                    return new Claimed(outputPptx, proj, 0);
                } catch (IOException e) {
                    return new Claimed(p.toAbsolutePath().normalize(), proj, 0);
                }
            }
        }
        if (Files.isRegularFile(outputPptx) && Files.size(outputPptx) > 0) {
            return new Claimed(outputPptx, jobDir, 0);
        }

        Path projects = repo.resolve("projects");
        String jobKeyLower = jobKey == null ? "" : jobKey.trim().toLowerCase(Locale.ROOT);
        AtomicReference<Path> newest = new AtomicReference<>();
        AtomicReference<Instant> newestMod = new AtomicReference<>(Instant.EPOCH);
        if (Files.isDirectory(projects)) {
            try (Stream<Path> walk = Files.walk(projects)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx"))
                        .filter(p -> "exports".equalsIgnoreCase(p.getParent() == null ? "" : p.getParent().getFileName().toString()))
                        .forEach(p -> {
                            try {
                                Instant mod = Files.getLastModifiedTime(p).toInstant();
                                if (mod.isBefore(freshAfter)) {
                                    return;
                                }
                                if (StringUtils.hasText(jobKeyLower)
                                        && !p.toString().toLowerCase(Locale.ROOT).contains(jobKeyLower)) {
                                    return;
                                }
                                if (newest.get() == null || mod.isAfter(newestMod.get())) {
                                    newest.set(p);
                                    newestMod.set(mod);
                                }
                            } catch (IOException ignored) {
                                // skip
                            }
                        });
            }
        }
        Path found = newest.get();
        if (found == null) {
            throw new IOException("未找到本任务 PPTX（需 output.pptx / 新鲜 result 路径 / 含 jobKey 的 exports）");
        }
        Path proj = found.getParent() != null && found.getParent().getParent() != null
                ? found.getParent().getParent()
                : jobDir;
        try {
            Files.copy(found, outputPptx, StandardCopyOption.REPLACE_EXISTING);
            return new Claimed(outputPptx, proj, 0);
        } catch (IOException e) {
            return new Claimed(found.toAbsolutePath().normalize(), proj, 0);
        }
    }

    static boolean acceptClaimed(Path path, Path jobDir, String jobKey, Instant freshAfter) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            return false;
        }
        Path absJob = jobDir.toAbsolutePath().normalize();
        Path absP = path.toAbsolutePath().normalize();
        if (absP.startsWith(absJob)) {
            return true;
        }
        Instant mod = Files.getLastModifiedTime(path).toInstant();
        if (mod.isBefore(freshAfter)) {
            return false;
        }
        String jobKeyLower = jobKey == null ? "" : jobKey.trim().toLowerCase(Locale.ROOT);
        if (StringUtils.hasText(jobKeyLower) && absP.toString().toLowerCase(Locale.ROOT).contains(jobKeyLower)) {
            return true;
        }
        return true;
    }

    static Path projectDirOf(Path pptx, Path jobDir) {
        Path dir = pptx.getParent();
        if (dir != null && "exports".equalsIgnoreCase(dir.getFileName().toString())) {
            return dir.getParent() == null ? jobDir : dir.getParent();
        }
        return jobDir;
    }

    static String readResultPath(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return "";
        }
        int nl = raw.indexOf('\n');
        String line = nl >= 0 ? raw.substring(0, nl).trim() : raw;
        line = line.replaceAll("^[\"'`]+|[\"'`]+$", "");
        int md = line.indexOf("](");
        if (md >= 0) {
            String rest = line.substring(md + 2);
            int close = rest.indexOf(')');
            if (close >= 0) {
                line = rest.substring(0, close).trim();
            }
        }
        if (line.startsWith("file://")) {
            line = line.substring("file://".length());
        }
        if (line.length() >= 3 && line.charAt(0) == '/' && line.charAt(2) == ':') {
            line = line.substring(1);
        }
        return line;
    }

    static String classifyIdle(String log) {
        if (log == null || log.isBlank()) {
            return "";
        }
        String low = log.toLowerCase(Locale.ROOT);
        String[] markers = {
                "请告诉我", "想要生成什么", "您可以提供", "准备就绪", "没有收到具体主题",
                "没有收到主题", "未收到主题", "无法确认现有文件", "现有文件是否就是本次",
                "请提供主题", "请提供素材", "what would you like", "please tell me",
                "ready to help", "no topic", "didn't receive", "did not receive"
        };
        for (String m : markers) {
            if (log.contains(m) || low.contains(m.toLowerCase(Locale.ROOT))) {
                return "（Agent 疑似空转/未执行 brief 或误认旧 PPTX；请检查 prompt.md 是否被阅读）";
            }
        }
        return "";
    }
}

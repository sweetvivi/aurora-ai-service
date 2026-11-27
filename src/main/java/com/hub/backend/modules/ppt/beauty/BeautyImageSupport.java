package com.hub.backend.modules.ppt.beauty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.pptmaster.agent.AgentGenerateRequest;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class BeautyImageSupport {

    private BeautyImageSupport() {
    }

    record MappedImages(ObjectNode manifest, List<ObjectNode> skipped, List<String> warnings) {
    }

    static MappedImages mapManifest(ObjectMapper mapper, String raw04) throws Exception {
        JsonNode root = mapper.readTree(raw04);
        String styleLock = root.path("styleLock").asText("").trim();
        ArrayNode items = mapper.createArrayNode();
        List<ObjectNode> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode manifest = root.path("imageManifest");
        if (manifest.isArray()) {
            for (JsonNode row : manifest) {
                String id = row.path("id").asText("").trim();
                if (!StringUtils.hasText(id)) {
                    warnings.add("跳过无 id 的 imageManifest 行");
                    continue;
                }
                String via = row.path("acquireVia").asText("").trim().toLowerCase(Locale.ROOT);
                if ("ai".equals(via)) {
                    String prompt = row.path("prompt").asText("").trim();
                    String lock = row.path("styleLock").asText("").trim();
                    if (!StringUtils.hasText(lock)) {
                        lock = styleLock;
                    }
                    if (StringUtils.hasText(lock) && !prompt.contains(lock)) {
                        prompt = (prompt + ". Style lock: " + lock).trim();
                    }
                    String neg = row.path("negativePrompt").asText("").trim();
                    if (StringUtils.hasText(neg)) {
                        prompt = (prompt + ". Avoid: " + neg).trim();
                    }
                    if (!StringUtils.hasText(prompt)) {
                        warnings.add(id + ": AI 行无 prompt，跳过");
                        skipped.add(skippedNode(mapper, id, row.path("pages"), "empty prompt"));
                        continue;
                    }
                    String ar = row.path("aspectRatio").asText("16:9").trim();
                    if (!StringUtils.hasText(ar)) {
                        ar = "16:9";
                    }
                    ObjectNode item = mapper.createObjectNode();
                    item.put("filename", sanitizeFilename(id) + ".png");
                    item.put("prompt", prompt);
                    item.put("aspect_ratio", ar);
                    item.put("status", "Pending");
                    item.put("text_policy", "none");
                    item.put("page_role", mapPageRole(row.path("role").asText("")));
                    item.put("alt_text", id);
                    item.put("purpose", row.path("role").asText("").trim());
                    items.add(item);
                } else {
                    String reason = "acquireVia=" + via + " 本阶段不执行";
                    if (!StringUtils.hasText(via)) {
                        reason = "acquireVia 为空";
                    }
                    warnings.add(id + ": " + reason);
                    skipped.add(skippedNode(mapper, id, row.path("pages"), reason));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("items", items);
        return new MappedImages(out, skipped, warnings);
    }

    static List<ObjectNode> runImageGen(ObjectMapper mapper,
                                        Path repo,
                                        String python,
                                        Path imagesDir,
                                        ObjectNode manifest,
                                        AgentGenerateRequest.ImageLlmConfig img) throws Exception {
        if (img == null || !StringUtils.hasText(img.apiKey()) || !StringUtils.hasText(img.baseUrl())
                || !StringUtils.hasText(img.model())) {
            throw new ServiceUnavailableException("需要 AI 配图但未配置生图模型");
        }
        Files.createDirectories(imagesDir);
        Path manifestPath = imagesDir.resolve("image_prompts.json");
        Files.writeString(manifestPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        Path script = repo.resolve("skills").resolve("ppt-master").resolve("scripts").resolve("image_gen.py");
        if (!Files.isRegularFile(script)) {
            throw new ServiceUnavailableException("文生图脚本不可用，请稍后重试");
        }
        String py = StringUtils.hasText(python) ? python : "python";
        Path logPath = imagesDir.resolve("image_gen.log");
        List<String> command = List.of(py, script.toString(), "--manifest", manifestPath.toString());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repo.resolve("skills").resolve("ppt-master").resolve("scripts").toFile());
        Map<String, String> env = pb.environment();
        env.put("IMAGE_BACKEND", "openai");
        env.put("OPENAI_API_KEY", img.apiKey().trim());
        env.put("OPENAI_BASE_URL", img.baseUrl().trim());
        env.put("OPENAI_MODEL", img.model().trim());
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        pb.redirectOutput(logPath.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new ServiceUnavailableException("image_gen 超时");
        }
        if (process.exitValue() != 0) {
            throw new ServiceUnavailableException("文生图失败，请稍后重试");
        }
        JsonNode after = mapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        List<ObjectNode> resolved = new ArrayList<>();
        for (JsonNode it : after.path("items")) {
            String filename = it.path("filename").asText("");
            String status = it.path("status").asText("");
            String id = it.path("alt_text").asText("");
            if (!StringUtils.hasText(id)) {
                int dot = filename.lastIndexOf('.');
                id = dot > 0 ? filename.substring(0, dot) : filename;
            }
            Path file = imagesDir.resolve(filename);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", id);
            if ("ok".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status) || Files.isRegularFile(file)) {
                node.put("localPath", file.toAbsolutePath().normalize().toString());
                node.put("skipped", false);
            } else {
                node.put("skipped", true);
                node.put("reason", "image_gen status=" + status);
            }
            resolved.add(node);
        }
        return resolved;
    }

    private static ObjectNode skippedNode(ObjectMapper mapper, String id, JsonNode pages, String reason) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("skipped", true);
        n.put("reason", reason);
        if (pages != null && pages.isArray()) {
            n.set("pages", pages);
        }
        return n;
    }

    private static String sanitizeFilename(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String mapPageRole(String role) {
        return switch (role == null ? "" : role.trim().toLowerCase(Locale.ROOT)) {
            case "cover_hero", "section_atmosphere", "background" -> "background";
            default -> "content";
        };
    }
}

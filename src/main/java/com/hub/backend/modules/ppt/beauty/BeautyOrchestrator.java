package com.hub.backend.modules.ppt.beauty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.pptmaster.agent.AgentGenerateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Beauty stages 01–04 + optional image_gen (ported from go-admin beauty.Run).
 */
@Component
public class BeautyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BeautyOrchestrator.class);

    private static final Map<String, String> STAGE_FILES = Map.of(
            "01", "01-需求增强.md",
            "02", "02-内容策划.md",
            "03", "03-视觉系统设计.md",
            "04", "04-图片资源与生图.md",
            "05", "05-版面实现约束.md"
    );

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final PptProperties pptProperties;

    public BeautyOrchestrator(LlmClient llmClient, ObjectMapper objectMapper, PptProperties pptProperties) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.pptProperties = pptProperties;
    }

    public record Bundle(Path stageDir, int pageCount, List<String> warnings) {
    }

    public Bundle run(Options opts) {
        try {
            return runInternal(opts);
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("精品编排失败: {}", e.toString());
            throw new ServiceUnavailableException("精品编排失败，请稍后重试");
        }
    }

    private Bundle runInternal(Options opts) throws Exception {
        if (!StringUtils.hasText(opts.repo())) {
            throw new ServiceUnavailableException("精品编排服务暂不可用，请稍后重试");
        }
        Path jobDir = Path.of(opts.jobDir());
        Files.createDirectories(jobDir);
        Path stageDir = jobDir.resolve("stages");
        Files.createDirectories(stageDir);

        BiConsumer<Integer, String> progress = opts.onProgress() == null
                ? (p, m) -> {
                }
                : opts.onProgress();

        int n = opts.slideCount() < 4 ? 8 : opts.slideCount();
        String aspect = StringUtils.hasText(opts.aspectRatio()) ? opts.aspectRatio() : "16:9";
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("recommendedPageCount", n);
        constraints.put("slideCount", n);
        constraints.put("language", nullToEmpty(opts.language()));
        constraints.put("scene", nullToEmpty(opts.scene()));
        constraints.put("tone", nullToEmpty(opts.tone()));
        constraints.put("detail", nullToEmpty(opts.detail()));
        constraints.put("aspectRatio", aspect);
        constraints.put("autoImg", opts.autoImg());
        constraints.put("imagePolicyHint", opts.autoImg() ? "ai_selective" : "no_ai");

        progress.accept(12, "精美编排：需求增强…");
        String raw01 = runChatStage(opts, "01", buildUser01(opts.topic(), opts.content(), constraints));
        if (!opts.autoImg()) {
            raw01 = forceField(raw01, "imagePolicy", "no_ai");
        }
        Files.writeString(stageDir.resolve("01_brief.json"), raw01, StandardCharsets.UTF_8);

        progress.accept(22, "精美编排：内容策划…");
        Map<String, String> up02 = new LinkedHashMap<>();
        up02.put("stage01", raw01);
        String raw02 = runChatStage(opts, "02", buildUserUpstream(
                "请基于上游 Stage01 JSON 做内容策划。页数必须等于 Stage01.recommendedPageCount（若无则用约束 slideCount）。",
                up02, constraints));
        Files.writeString(stageDir.resolve("02_content.json"), raw02, StandardCharsets.UTF_8);
        int pageCount = extractPageCount(raw02, n);

        progress.accept(32, "精美编排：视觉系统设计…");
        Map<String, String> up03 = new LinkedHashMap<>();
        up03.put("stage01", raw01);
        up03.put("stage02", raw02);
        String raw03 = runChatStage(opts, "03", buildUserUpstream(
                "请基于 Stage01/02 建立自由设计系统与逐页 pageDesignPlans。禁止 templateId。",
                up03, constraints));
        Files.writeString(stageDir.resolve("03_visual.json"), raw03, StandardCharsets.UTF_8);

        progress.accept(42, "精美编排：图片资源清单…");
        Map<String, String> up04 = new LinkedHashMap<>();
        up04.put("stage01", raw01);
        up04.put("stage02", raw02);
        up04.put("stage03", raw03);
        String raw04 = runChatStage(opts, "04", buildUserUpstream(
                "请基于 Stage01–03 产出 imageManifest。遵守 imagePolicy；图表页不生图。",
                up04, constraints));
        if (!opts.autoImg()) {
            raw04 = forceEmptyManifest(raw04);
        }
        Files.writeString(stageDir.resolve("04_images.json"), raw04, StandardCharsets.UTF_8);

        BeautyImageSupport.MappedImages mapped = BeautyImageSupport.mapManifest(objectMapper, raw04);
        Files.writeString(stageDir.resolve("image_prompts.planned.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapped.manifest()),
                StandardCharsets.UTF_8);

        Path imagesDir = stageDir.resolve("images");
        Files.createDirectories(imagesDir);
        List<ObjectNode> aiResolved = new ArrayList<>();
        List<String> warnings = new ArrayList<>(mapped.warnings());
        if (opts.autoImg() && mapped.manifest().path("items").isArray()
                && !mapped.manifest().path("items").isEmpty()) {
            progress.accept(52, "精美编排：文生图…");
            aiResolved = BeautyImageSupport.runImageGen(
                    objectMapper,
                    Path.of(opts.repo()),
                    pptProperties.getPptMaster().getPython(),
                    imagesDir,
                    mapped.manifest(),
                    opts.imageLlm());
        } else {
            progress.accept(52, "精美编排：跳过文生图");
            ObjectNode empty = objectMapper.createObjectNode();
            empty.putArray("items");
            Files.writeString(imagesDir.resolve("image_prompts.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(empty),
                    StandardCharsets.UTF_8);
        }

        ArrayNode resolved = objectMapper.createArrayNode();
        resolved.addAll(aiResolved);
        for (ObjectNode skipped : mapped.skipped()) {
            resolved.add(skipped);
        }
        ObjectNode resolvedRoot = objectMapper.createObjectNode();
        resolvedRoot.set("resolvedAssets", resolved);
        resolvedRoot.set("warnings", objectMapper.valueToTree(warnings));
        Files.writeString(stageDir.resolve("resolved_assets.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedRoot),
                StandardCharsets.UTF_8);

        try {
            String sys05 = loadStageSystem(opts.repo(), "05");
            Files.writeString(stageDir.resolve("05_impl_constraints.md"), sys05, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Unable to copy beauty stage05 prompt: {}", e.getMessage());
        }

        progress.accept(60, "精美编排：规划完成，进入版面实现…");
        return new Bundle(stageDir.toAbsolutePath().normalize(), pageCount, warnings);
    }

    private String runChatStage(Options opts, String stage, String user) throws Exception {
        String system = loadStageSystem(opts.repo(), stage)
                + "\n\n# 输出格式\n仅输出合法 JSON 对象，不要 Markdown 围栏或解释。";
        ProviderCredentials credentials = new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                opts.chat().baseUrl(),
                opts.chat().apiKey(),
                null, null, null);
        var completion = llmClient.complete(new LlmChatCommand(
                opts.chat().model(),
                List.of(LlmChatMessage.system(system), LlmChatMessage.user(user)),
                0.4,
                8192,
                credentials));
        String text = completion.contentOrEmpty();
        String json = extractJson(text);
        mustBeautyFree(json);
        return json;
    }

    private String loadStageSystem(String repo, String stage) throws Exception {
        String name = STAGE_FILES.get(stage);
        if (name == null) {
            throw new ServiceUnavailableException("未知 beauty stage " + stage);
        }
        Path path = Path.of(repo).resolve("docs").resolve("提示词模板").resolve("beauty").resolve(name);
        if (!Files.isRegularFile(path)) {
            log.warn("beauty stage prompt missing: {}", path);
            throw new ServiceUnavailableException("精品编排配置缺失，请稍后重试");
        }
        String text = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (!StringUtils.hasText(text)) {
            log.warn("beauty stage prompt empty: {}", path);
            throw new ServiceUnavailableException("精品编排配置缺失，请稍后重试");
        }
        return text;
    }

    private String buildUser01(String topic, String content, ObjectNode constraints) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("## userTopic\n\n").append(nullToEmpty(topic)).append("\n\n");
        b.append("## userConstraints\n\n```json\n");
        b.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(constraints));
        b.append("\n```\n");
        if (StringUtils.hasText(content)) {
            String extra = content.trim();
            if (extra.codePointCount(0, extra.length()) > 12_000) {
                extra = extra.substring(0, extra.offsetByCodePoints(0, 12_000)) + "…";
            }
            b.append("\n## extraMaterials\n\n").append(extra).append('\n');
        }
        return b.toString();
    }

    private String buildUserUpstream(String instruction, Map<String, String> stages, ObjectNode constraints)
            throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(instruction).append("\n\n## userConstraints\n\n```json\n");
        b.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(constraints));
        b.append("\n```\n");
        for (Map.Entry<String, String> e : stages.entrySet()) {
            b.append("\n## ").append(e.getKey()).append("\n\n```json\n").append(e.getValue()).append("\n```\n");
        }
        return b.toString();
    }

    private String forceField(String raw, String field, String value) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(raw);
        node.put(field, value);
        return objectMapper.writeValueAsString(node);
    }

    private String forceEmptyManifest(String raw) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(raw);
        node.putArray("imageManifest");
        if (!node.has("iconNeeds")) {
            node.putArray("iconNeeds");
        }
        return objectMapper.writeValueAsString(node);
    }

    private int extractPageCount(String raw02, int fallback) {
        try {
            JsonNode node = objectMapper.readTree(raw02);
            int pc = node.path("pageCount").asInt(0);
            if (pc > 0) {
                return pc;
            }
            JsonNode pages = node.path("pages");
            if (pages.isArray() && !pages.isEmpty()) {
                return pages.size();
            }
        } catch (Exception ignored) {
            // fallback
        }
        return fallback;
    }

    private String extractJson(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ServiceUnavailableException("beauty stage 未返回 JSON 对象");
        }
        String json = t.substring(start, end + 1);
        objectMapper.readTree(json);
        return json;
    }

    private void mustBeautyFree(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (node.hasNonNull("templateId") && StringUtils.hasText(node.get("templateId").asText())) {
            throw new ServiceUnavailableException("beauty 输出不得包含 templateId");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public record Options(
            String repo,
            String jobDir,
            String topic,
            String content,
            int slideCount,
            String language,
            String scene,
            String tone,
            String detail,
            String aspectRatio,
            boolean autoImg,
            ChatConfig chat,
            AgentGenerateRequest.ImageLlmConfig imageLlm,
            BiConsumer<Integer, String> onProgress
    ) {
        public Duration stageTimeout(PptProperties props) {
            Duration d = props.getPptMaster().getBeautyStageTimeout();
            return d == null ? Duration.ofMinutes(8) : d;
        }
    }

    public record ChatConfig(String baseUrl, String apiKey, String model) {
    }
}

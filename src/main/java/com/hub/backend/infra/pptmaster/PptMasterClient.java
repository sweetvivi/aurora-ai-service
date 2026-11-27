package com.hub.backend.infra.pptmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.pptmaster.agent.AgentGenerateRequest;
import com.hub.backend.infra.pptmaster.agent.PptAgentClient;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for the local ppt-master worker (process or HTTP).
 */
@Component
public class PptMasterClient {

    private static final Logger log = LoggerFactory.getLogger(PptMasterClient.class);

    private final PptProperties pptProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final PptModelResolver modelResolver;
    private final PptAgentClient pptAgentClient;
    /** 站点当前有效默认模型；不能用 llmProperties.getDefaultModel()，目录改名后那个值会失效 */
    private final ModelUseResolver llmModelResolver;

    public PptMasterClient(PptProperties pptProperties,
                           LlmProperties llmProperties,
                           ObjectMapper objectMapper,
                           @Qualifier("llmHttpClient") HttpClient httpClient,
                           PptModelResolver modelResolver,
                           PptAgentClient pptAgentClient,
                           ModelUseResolver llmModelResolver) {
        this.pptProperties = pptProperties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.modelResolver = modelResolver;
        this.pptAgentClient = pptAgentClient;
        this.llmModelResolver = llmModelResolver;
    }

    public PptMasterGenerationResult generate(PptMasterGenerateRequest request) {
        ResolvedCredentials credentials = resolveCredentials(request);
        PptProperties.PptMaster cfg = pptProperties.getPptMaster();
        if (cfg.useAgent() || isAgentGenerateMode(request.generateMode())) {
            return pptAgentClient.generate(toAgentRequest(request, credentials));
        }
        ObjectNode body = buildBody(request, credentials);
        JsonNode response = cfg.useHttp() ? invokeHttp(body, cfg) : invokeProcess(body, cfg);
        if (response == null || !response.path("ok").asBoolean(false)) {
            String err = response == null ? "empty worker response" : response.path("error").asText("unknown error");
            throw new ServiceUnavailableException("ppt-master 生成失败: " + err);
        }
        String pptx = response.path("pptx").asText(null);
        if (!StringUtils.hasText(pptx)) {
            throw new ServiceUnavailableException("ppt-master 未返回 pptx 路径");
        }
        Path path = Path.of(pptx);
        if (!Files.isRegularFile(path)) {
            throw new ServiceUnavailableException("ppt-master pptx 文件不存在: " + pptx);
        }
        int slides = response.path("slides").asInt(request.nSlides() == null ? 0 : request.nSlides());
        String project = response.path("project").asText(null);
        return new PptMasterGenerationResult(path, slides, project);
    }

    /** Resolve text/image model credentials for beauty orchestration and Agent. */
    public ResolvedCredentials resolveCredentials(PptMasterGenerateRequest request) {
        String fallbackTextKey = StringUtils.hasText(request.model())
                ? request.model()
                : (StringUtils.hasText(pptProperties.getPptMaster().getModel())
                        ? pptProperties.getPptMaster().getModel()
                        : llmModelResolver.defaultModelForSite(request.siteId()));
        ProviderCredentials fallbackText = new ProviderCredentials(
                "OPENAI_COMPATIBLE", llmProperties.resolvedBaseUrl(),
                StringUtils.hasText(request.apiKey()) ? request.apiKey() : llmProperties.getApiKey(),
                null, null, null);
        PptModelResolver.ResolvedModel text = modelResolver.resolve(
                request.siteId(), request.model(), fallbackTextKey, LlmModelEntity.TYPE_CHAT, fallbackText);
        ProviderCredentials textCredentials = text.credentials();
        boolean allowOffline = pptProperties.getPptMaster().isAllowOffline();
        if ((textCredentials == null || !textCredentials.hasApiKey()) && !allowOffline) {
            throw new ServiceUnavailableException(
                    "未配置可用的文本模型 API Key，请在后台为所选 CHAT 模型绑定有效 Provider");
        }

        // 图片默认值同样要过目录：静态配置指向已退役的 key 时，这里会先于任何调用抛 404。
        String fallbackImageKey = StringUtils.hasText(request.imageModel())
                ? request.imageModel() : llmModelResolver.defaultImageModelForSite(request.siteId());
        String textApiKey = textCredentials == null ? null : textCredentials.apiKey();
        ProviderCredentials fallbackImage = new ProviderCredentials(
                "OPENAI_COMPATIBLE", llmProperties.resolvedBaseUrl(),
                StringUtils.hasText(llmProperties.getImageApiKey())
                        ? llmProperties.getImageApiKey() : textApiKey,
                null, llmProperties.getImagesPath(), null);
        PptModelResolver.ResolvedModel image = modelResolver.resolve(
                request.siteId(), request.imageModel(), fallbackImageKey, LlmModelEntity.TYPE_IMAGE, fallbackImage);
        ProviderCredentials imageCredentials = image.credentials();
        if (request.autoImg()
                && (imageCredentials == null || !imageCredentials.hasApiKey())) {
            throw new ServiceUnavailableException(
                    "已开启自动配图，但所选 IMAGE 模型没有可用 API Key，请在后台绑定图片 Provider");
        }
        return new ResolvedCredentials(text, image);
    }

    private AgentGenerateRequest toAgentRequest(PptMasterGenerateRequest request, ResolvedCredentials creds) {
        ProviderCredentials text = creds.text().credentials();
        ProviderCredentials image = creds.image().credentials();
        AgentGenerateRequest.ImageLlmConfig imageLlm = null;
        if (image != null && image.hasApiKey()) {
            imageLlm = new AgentGenerateRequest.ImageLlmConfig(
                    baseUrl(image),
                    image.apiKey(),
                    creds.image().upstreamModel(),
                    imagesPath(image));
        }
        return new AgentGenerateRequest(
                request.jobId(),
                request.title(),
                request.content(),
                request.nSlides(),
                request.language(),
                request.scene(),
                request.tone(),
                request.detail(),
                request.autoImg(),
                request.imgDensity(),
                request.templateId(),
                request.aspectRatio(),
                request.generateMode(),
                request.deckTemplateId(),
                request.deckTemplateName(),
                request.deckStyleBlurb(),
                request.deckSpecJson(),
                request.deckWorkspacePath(),
                request.beautyStageDir(),
                new AgentGenerateRequest.LlmConfig(
                        baseUrl(text),
                        creds.text().upstreamModel(),
                        text == null ? "" : text.apiKey(),
                        text == null ? null : text.chatPath()),
                imageLlm,
                request.siteId());
    }

    private static boolean isAgentGenerateMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return false;
        }
        String m = mode.trim().toLowerCase();
        return "agent".equals(m) || "template".equals(m) || "beauty".equals(m);
    }

    public byte[] readPptx(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ServiceUnavailableException("读取生成结果失败，请稍后重试");
        }
    }

    private ObjectNode buildBody(PptMasterGenerateRequest request, ResolvedCredentials creds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jobId", request.jobId());
        body.put("title", request.title() == null ? "" : request.title());
        body.put("content", request.content());
        body.put("nSlides", request.nSlides() == null ? 12 : request.nSlides());
        body.put("language", request.language() == null ? "Chinese" : request.language());
        body.put("scene", request.scene() == null ? "" : request.scene());
        body.put("tone", request.tone() == null ? "" : request.tone());
        body.put("detail", request.detail() == null ? "" : request.detail());
        body.put("workDir", pptProperties.getPptMaster().getWorkDir());
        body.put("autoImg", request.autoImg());
        body.put("imgDensity", normalizeDensity(request.imgDensity()));
        body.put("templateId", StringUtils.hasText(request.templateId()) ? request.templateId() : "business-clean");
        body.put("aspectRatio", StringUtils.hasText(request.aspectRatio()) ? request.aspectRatio() : "16:9");

        PptModelResolver.ResolvedModel text = creds.text();
        ProviderCredentials textCredentials = text.credentials();
        boolean allowOffline = pptProperties.getPptMaster().isAllowOffline();
        ObjectNode llm = body.putObject("llm");
        llm.put("baseUrl", baseUrl(textCredentials));
        llm.put("model", text.upstreamModel() == null ? "" : text.upstreamModel());
        if (textCredentials != null && StringUtils.hasText(textCredentials.chatPath())) {
            llm.put("chatPath", textCredentials.chatPath());
        }
        llm.put("imageModel", StringUtils.hasText(request.imageModel())
                ? request.imageModel()
                : llmModelResolver.defaultImageModelForSite(request.siteId()));
        llm.put("imagesPath", StringUtils.hasText(llmProperties.getImagesPath())
                ? llmProperties.getImagesPath()
                : "/images/generations");
        int imageConcurrency = pptProperties.getPptMaster().getImageConcurrency();
        llm.put("imageConcurrency", imageConcurrency > 0 ? imageConcurrency : 2);
        long imageTimeoutSec = llmProperties.getImageTimeout() == null
                ? 240L
                : Math.max(60L, llmProperties.getImageTimeout().toSeconds());
        llm.put("imageTimeoutSec", imageTimeoutSec);
        String textApiKey = textCredentials == null ? null : textCredentials.apiKey();
        llm.put("apiKey", textApiKey == null ? "" : textApiKey);

        PptModelResolver.ResolvedModel image = creds.image();
        ProviderCredentials imageCredentials = image.credentials();
        ObjectNode imageLlm = body.putObject("imageLlm");
        imageLlm.put("baseUrl", baseUrl(imageCredentials));
        imageLlm.put("apiKey", imageCredentials == null || imageCredentials.apiKey() == null
                ? "" : imageCredentials.apiKey());
        imageLlm.put("model", image.upstreamModel() == null ? "" : image.upstreamModel());
        imageLlm.put("imagesPath", imagesPath(imageCredentials));
        imageLlm.put("imageTimeoutSec", llmProperties.getImageTimeout() == null
                ? 240L : Math.max(60L, llmProperties.getImageTimeout().toSeconds()));
        body.put("offline", !StringUtils.hasText(textApiKey) && allowOffline);
        return body;
    }

    private String baseUrl(ProviderCredentials credentials) {
        return credentials != null && credentials.hasBaseUrl()
                ? credentials.baseUrl().trim().replaceAll("/+$", "")
                : llmProperties.resolvedBaseUrl();
    }

    private String imagesPath(ProviderCredentials credentials) {
        return credentials != null && StringUtils.hasText(credentials.imagesPath())
                ? credentials.imagesPath() : "/images/generations";
    }

    public static String normalizeDensity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "mid";
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "light", "sparse", "low" -> "light";
            case "heavy", "core", "high", "rich" -> "heavy";
            default -> "mid";
        };
    }

    public static String normalizeAspectRatio(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "16:9";
        }
        String value = raw.trim().replace('/', ':');
        return switch (value) {
            case "16:9", "4:3", "9:16", "1:1" -> value;
            default -> "16:9";
        };
    }

    private JsonNode invokeHttp(ObjectNode body, PptProperties.PptMaster cfg) {
        String base = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl().trim().replaceAll("/+$", "");
        if (!StringUtils.hasText(base)) {
            throw new ServiceUnavailableException("未配置 app.ppt.ppt-master.base-url");
        }
        Duration timeout = cfg.getGenerateTimeout() == null ? Duration.ofMinutes(20) : cfg.getGenerateTimeout();
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(base + "/v1/generate"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new ServiceUnavailableException(
                        "ppt-master HTTP " + response.statusCode() + ": " + trim(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceUnavailableException("调用生成服务失败，请稍后重试");
        }
    }

    private JsonNode invokeProcess(ObjectNode body, PptProperties.PptMaster cfg) {
        Path repo = Path.of(cfg.getRepoRoot() == null ? "" : cfg.getRepoRoot()).toAbsolutePath().normalize();
        Path script = repo.resolve("worker").resolve("generate.py");
        if (!Files.isRegularFile(script)) {
            throw new ServiceUnavailableException("找不到 ppt-master worker: " + script);
        }
        String python = StringUtils.hasText(cfg.getPython()) ? cfg.getPython() : "python";
        Duration timeout = cfg.getGenerateTimeout() == null ? Duration.ofMinutes(20) : cfg.getGenerateTimeout();
        Path work = Path.of(cfg.getWorkDir() == null ? "./data/ppt-master-jobs" : cfg.getWorkDir())
                .toAbsolutePath().normalize();
        Path requestFile = null;
        try {
            Files.createDirectories(work);
            requestFile = work.resolve(body.path("jobId").asText("job") + ".request.json");
            Path stdoutFile = work.resolve(body.path("jobId").asText("job") + ".stdout.log");
            Path stderrFile = work.resolve(body.path("jobId").asText("job") + ".stderr.log");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(requestFile.toFile(), body);

            List<String> command = new ArrayList<>();
            command.add(python);
            command.add(script.toString());
            command.add("--request");
            command.add(requestFile.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repo.toFile());
            // Force UTF-8 so Windows GBK console encoding cannot corrupt logs / JSON.
            Map<String, String> env = pb.environment();
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONIOENCODING", "utf-8");
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            Process process = pb.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceUnavailableException("ppt-master 生成超时 (" + timeout.toMinutes() + "m)");
            }
            String stdout = readProcessLog(stdoutFile);
            String stderr = readProcessLog(stderrFile);
            if (StringUtils.hasText(stderr)) {
                log.info("ppt-master worker stderr jobId={}: {}", body.path("jobId").asText(), trim(stderr));
            }
            int code = process.exitValue();
            String jsonLine = lastJsonLine(stdout);
            if (!StringUtils.hasText(jsonLine)) {
                throw new ServiceUnavailableException(
                        "ppt-master 无 JSON 输出 (exit=" + code + "): " + trim(stdout + "\n" + stderr));
            }
            JsonNode node = objectMapper.readTree(jsonLine);
            if (code != 0 && !node.path("ok").asBoolean(false)) {
                throw new ServiceUnavailableException(
                        "ppt-master exit " + code + ": " + node.path("error").asText(trim(stderr)));
            }
            return node;
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ppt-master worker invoke failed", e);
            throw new ServiceUnavailableException("启动生成服务失败，请稍后重试");
        } finally {
            if (requestFile != null) {
                try {
                    Files.deleteIfExists(requestFile);
                } catch (IOException cleanupError) {
                    log.debug("Unable to remove ppt-master request file {}", requestFile, cleanupError);
                }
            }
        }
    }

    /**
     * Worker logs on Chinese Windows are often GBK when PYTHONUTF8 is unset.
     * {@link Files#readString} rejects malformed UTF-8 with {@code Input length = 1}.
     */
    static String readProcessLog(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            return "";
        }
        if (decodesFully(bytes, StandardCharsets.UTF_8)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        Charset gbk = Charset.forName("GBK");
        if (decodesFully(bytes, gbk)) {
            return new String(bytes, gbk);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean decodesFully(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String lastJsonLine(String stdout) {
        if (!StringUtils.hasText(stdout)) {
            return null;
        }
        String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        String trimmed = stdout.trim();
        return trimmed.startsWith("{") ? trimmed : null;
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() > 800 ? t.substring(0, 800) + "…" : t;
    }

    public record PptMasterGenerateRequest(
            String jobId,
            String title,
            String content,
            Integer nSlides,
            String language,
            String scene,
            String tone,
            String detail,
            String model,
            String apiKey,
            String imageModel,
            boolean autoImg,
            String imgDensity,
            String templateId,
            String aspectRatio,
            Long siteId,
            String generateMode,
            String deckTemplateId,
            String deckTemplateName,
            String deckStyleBlurb,
            String deckSpecJson,
            String deckWorkspacePath,
            String beautyStageDir
    ) {
        /** Backward-compatible constructor for worker-only callers. */
        public PptMasterGenerateRequest(
                String jobId,
                String title,
                String content,
                Integer nSlides,
                String language,
                String scene,
                String tone,
                String detail,
                String model,
                String apiKey,
                String imageModel,
                boolean autoImg,
                String imgDensity,
                String templateId,
                String aspectRatio,
                Long siteId) {
            this(jobId, title, content, nSlides, language, scene, tone, detail, model, apiKey, imageModel,
                    autoImg, imgDensity, templateId, aspectRatio, siteId,
                    null, null, null, null, null, null, null);
        }

        public PptMasterGenerateRequest withGenerateMode(String mode) {
            return new PptMasterGenerateRequest(
                    jobId, title, content, nSlides, language, scene, tone, detail, model, apiKey, imageModel,
                    autoImg, imgDensity, templateId, aspectRatio, siteId,
                    mode, deckTemplateId, deckTemplateName, deckStyleBlurb, deckSpecJson,
                    deckWorkspacePath, beautyStageDir);
        }

        public PptMasterGenerateRequest withBeautyStageDir(String dir) {
            return new PptMasterGenerateRequest(
                    jobId, title, content, nSlides, language, scene, tone, detail, model, apiKey, imageModel,
                    autoImg, imgDensity, templateId, aspectRatio, siteId,
                    generateMode, deckTemplateId, deckTemplateName, deckStyleBlurb, deckSpecJson,
                    deckWorkspacePath, dir);
        }

        public PptMasterGenerateRequest withNSlides(Integer slides) {
            return new PptMasterGenerateRequest(
                    jobId, title, content, slides, language, scene, tone, detail, model, apiKey, imageModel,
                    autoImg, imgDensity, templateId, aspectRatio, siteId,
                    generateMode, deckTemplateId, deckTemplateName, deckStyleBlurb, deckSpecJson,
                    deckWorkspacePath, beautyStageDir);
        }
    }

    public record PptMasterGenerationResult(Path pptxPath, int slides, String projectPath) {
    }

    public record ResolvedCredentials(
            PptModelResolver.ResolvedModel text,
            PptModelResolver.ResolvedModel image
    ) {
    }
}

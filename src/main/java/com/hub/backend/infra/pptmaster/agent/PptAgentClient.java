package com.hub.backend.infra.pptmaster.agent;

import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.pptmaster.PptMasterClient.PptMasterGenerationResult;
import com.hub.backend.infra.pptmaster.PptProcessRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spawns local Claude Code CLI against ppt-master Skill (ported from go-admin generateAgent).
 */
@Component
public class PptAgentClient {

    private static final Logger log = LoggerFactory.getLogger(PptAgentClient.class);

    private final PptProperties pptProperties;
    private final PptProcessRegistry processRegistry;

    public PptAgentClient(PptProperties pptProperties, PptProcessRegistry processRegistry) {
        this.pptProperties = pptProperties;
        this.processRegistry = processRegistry;
    }

    public PptMasterGenerationResult generate(AgentGenerateRequest req) {
        PptProperties.PptMaster cfg = pptProperties.getPptMaster();
        Path repo = Path.of(cfg.getRepoRoot() == null ? "" : cfg.getRepoRoot()).toAbsolutePath().normalize();
        Path skill = repo.resolve("skills").resolve("ppt-master").resolve("SKILL.md");
        if (!Files.isRegularFile(skill)) {
            throw new ServiceUnavailableException("ppt-master Skill 不存在: " + skill);
        }

        String baseUrl = anthropicCompatibleBaseUrl(
                req.llm() != null && StringUtils.hasText(req.llm().baseUrl()) ? req.llm().baseUrl() : null);
        String token = req.llm() != null && StringUtils.hasText(req.llm().apiKey()) ? req.llm().apiKey().trim() : "";
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = anthropicCompatibleBaseUrl(cfg.getAgentBaseUrl());
        }
        if (!StringUtils.hasText(token)) {
            token = cfg.getAgentAuthToken() == null ? "" : cfg.getAgentAuthToken().trim();
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            throw new ServiceUnavailableException(
                    "Agent 中转未配置：请在后台为所选模型的厂商填写 API Key，或设置 app.ppt.ppt-master.agent-base-url / agent-auth-token");
        }

        Path workDir = Path.of(StringUtils.hasText(cfg.getWorkDir()) ? cfg.getWorkDir() : "./data/ppt-master-jobs")
                .toAbsolutePath().normalize();
        String jobKey = AgentBriefBuilder.sanitizeJobId(req.jobId());
        Path jobDir = workDir.resolve(jobKey);
        try {
            Files.createDirectories(jobDir);
        } catch (Exception e) {
            throw new ServiceUnavailableException("创建任务目录失败，请稍后重试");
        }

        Path briefPath = jobDir.resolve("brief.md");
        Path promptPath = jobDir.resolve("prompt.md");
        Path resultPath = jobDir.resolve("result.txt");
        Path outputPptx = jobDir.resolve("output.pptx");
        Path logPath = jobDir.resolve("agent.log");
        try {
            Files.deleteIfExists(resultPath);
            Files.deleteIfExists(outputPptx);
            String brief = AgentBriefBuilder.buildBrief(req);
            Files.writeString(briefPath, brief, StandardCharsets.UTF_8);
            String prompt = AgentBriefBuilder.buildPrompt(
                    briefPath.toAbsolutePath().toString(),
                    outputPptx.toAbsolutePath().toString(),
                    resultPath.toAbsolutePath().toString(),
                    req);
            Files.writeString(promptPath, prompt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceUnavailableException("写入任务配置失败，请稍后重试");
        }

        String claude = StringUtils.hasText(cfg.getClaudeBin()) ? cfg.getClaudeBin() : "claude";
        String model = StringUtils.hasText(cfg.getAgentModel()) ? cfg.getAgentModel() : "sonnet";
        if (req.llm() != null && StringUtils.hasText(req.llm().model())) {
            model = req.llm().model().trim();
        }

        String shortPrompt = "【无人值守】立即阅读并严格执行 UTF-8 文件（禁止寒暄/提问/核验旧 PPTX）："
                + promptPath.toAbsolutePath();
        List<String> command = new ArrayList<>();
        command.add(claude);
        command.add("-p");
        command.add(shortPrompt);
        command.add("--dangerously-skip-permissions");
        command.add("--add-dir");
        command.add(jobDir.toAbsolutePath().toString());
        if (StringUtils.hasText(req.deckWorkspacePath())) {
            command.add("--add-dir");
            command.add(req.deckWorkspacePath().trim());
        }
        if (StringUtils.hasText(req.beautyStageDir())) {
            command.add("--add-dir");
            command.add(req.beautyStageDir().trim());
        }
        command.add("--output-format");
        command.add("text");

        Duration timeout = cfg.getGenerateTimeout() == null ? Duration.ofMinutes(45) : cfg.getGenerateTimeout();
        Instant started = Instant.now();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repo.toFile());
            Map<String, String> env = pb.environment();
            env.put("ANTHROPIC_BASE_URL", baseUrl);
            env.put("ANTHROPIC_AUTH_TOKEN", token);
            env.put("ANTHROPIC_MODEL", model);
            env.put("ANTHROPIC_API_KEY", "");
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONIOENCODING", "utf-8");
            if (!"beauty".equalsIgnoreCase(trim(req.generateMode())) && req.autoImg() && req.imageLlm() != null) {
                AgentGenerateRequest.ImageLlmConfig img = req.imageLlm();
                if (StringUtils.hasText(img.apiKey()) && StringUtils.hasText(img.baseUrl()) && StringUtils.hasText(img.model())) {
                    env.put("IMAGE_BACKEND", "openai");
                    env.put("OPENAI_API_KEY", img.apiKey().trim());
                    env.put("OPENAI_BASE_URL", img.baseUrl().trim());
                    env.put("OPENAI_MODEL", img.model().trim());
                }
            }
            pb.redirectOutput(logPath.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();
            processRegistry.register(req.jobId(), process);
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceUnavailableException("Claude Agent 生成超时 (" + timeout.toMinutes() + "m)");
            }
            int exit = process.exitValue();
            String tail = readLog(logPath);
            String idleHint = AgentOutputResolver.classifyIdle(tail);
            AgentOutputResolver.Claimed claimed;
            try {
                claimed = AgentOutputResolver.resolve(repo, jobDir, jobKey, outputPptx, resultPath, started);
            } catch (Exception findErr) {
                if (StringUtils.hasText(idleHint) || exit != 0) {
                    throw new ServiceUnavailableException(
                            "Claude Agent 未产出 PPTX: " + findErr.getMessage() + idleHint
                                    + "; log=" + truncate(tail, 400));
                }
                throw new ServiceUnavailableException(
                        "Claude Agent 未产出 PPTX: " + findErr.getMessage() + "; log=" + truncate(tail, 400));
            }
            if (StringUtils.hasText(idleHint)) {
                throw new ServiceUnavailableException(
                        "Agent 空转/未执行 brief，拒绝认领产物" + idleHint + "; log=" + truncate(tail, 400));
            }
            log.info("Claude Agent finished jobId={} exit={} pptx={}", req.jobId(), exit, claimed.pptx());
            return new PptMasterGenerationResult(
                    claimed.pptx(),
                    claimed.slides(),
                    claimed.project() == null ? null : claimed.project().toString());
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new ServiceUnavailableException("Claude Agent 被中断");
        } catch (Exception e) {
            throw new ServiceUnavailableException("启动生成任务失败，请稍后重试");
        } finally {
            processRegistry.unregister(req.jobId());
        }
    }

    public static String anthropicCompatibleBaseUrl(String u) {
        if (!StringUtils.hasText(u)) {
            return "";
        }
        String s = u.trim().replaceAll("/+$", "");
        if (s.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            s = s.substring(0, s.length() - 3).replaceAll("/+$", "");
        }
        return s;
    }

    private static String readLog(Path path) {
        try {
            if (!Files.exists(path)) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}

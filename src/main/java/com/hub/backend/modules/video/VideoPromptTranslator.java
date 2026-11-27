package com.hub.backend.modules.video;

import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelFallbackInvoker;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites a Chinese video prompt into English before it reaches the supplier.
 *
 * <p><b>当前未启用（未注册为 Bean，{@link VideoGenerationService} 直接提交原始提示词）。</b>
 * 当初启用时的前提——“上游视频模型只认英文提示词，中文会 400 或被忽略”——写在提交
 * 7bf83bd 的说明里，但从未核对过供应商文档，也没有实测记录（注释引用的
 * docs/video-generation-api.md 并不存在）。2026-11 文档核查结论：本项目相关模型
 * （grok-imagine-video、Seedance 2.0、可灵 2.5、Wan 2.5、海螺 02）官方均支持中文提示词；
 * 翻译只有代价没有收益：每次提交多一次 LLM 调用，转写可能丢失细节，且 Grok / Wan 的
 * 原生音频台词（“他说：欢迎来到西安”）会被英化成英文语音，直接违背用户意图。</p>
 *
 * <p>将来若接入确证的英文-only 模型，再恢复 {@code @Component} 并在提交处按模型能力调用。
 * 以下行为保持不变：翻译是 best effort——模型不可用、未配置或返回不可用内容时，
 * 原样提交用户的原文，绝不让一次辅助调用失败阻断视频任务。</p>
 */
public class VideoPromptTranslator {

    private static final Logger log = LoggerFactory.getLogger(VideoPromptTranslator.class);

    /** Anything outside Basic Latin plus the usual punctuation means the prompt is not English. */
    private static final Pattern NON_ASCII = Pattern.compile("[^\\x00-\\x7F]");

    private static final String SYSTEM = """
            You translate prompts for a text-to-video model that only understands English.

            Rules:
            - Output the English prompt only. No explanation, no quotes, no markdown, no prefix.
            - Keep every concrete detail: subject, appearance, action, setting, lighting, camera
              movement, shot size, style words and pacing.
            - Keep numbers, aspect ratios and technical terms such as 4K or 60fps as they are.
            - Do not add details the original does not have.
            - If the text is already English, return it unchanged.
            """;

    private final LlmClient llmClient;
    private final ModelUseResolver modelResolver;
    private final UserRelayCredentialResolver relayCredentials;

    public VideoPromptTranslator(LlmClient llmClient,
                                 ModelUseResolver modelResolver,
                                 UserRelayCredentialResolver relayCredentials) {
        this.llmClient = llmClient;
        this.modelResolver = modelResolver;
        this.relayCredentials = relayCredentials;
    }

    /**
     * @return the prompt to send upstream: the translation when one was produced, otherwise the
     *         original text unchanged
     */
    public String toUpstreamPrompt(CurrentUser currentUser, String prompt) {
        if (prompt == null || prompt.isBlank() || isAscii(prompt)) {
            return prompt;
        }

        try {
            List<ModelUseResolver.Resolved> models = modelResolver.resolveAllForSite(
                    currentUser.siteId(), null, LlmModelEntity.TYPE_CHAT);
            String translated = ModelFallbackInvoker.invoke(
                            models,
                            model -> {
                                String content = llmClient.complete(new LlmChatCommand(
                                                model.upstreamKey(),
                                                List.of(LlmChatMessage.system(SYSTEM),
                                                        LlmChatMessage.user(prompt)),
                                                0.0,
                                                // Generous enough for a long Chinese prompt plus the
                                                // answer; the translator must not silently cut the
                                                // description short.
                                                2048,
                                                relayCredentials.resolveKeyOnly(
                                                        currentUser, model.id())))
                                        .contentOrEmpty()
                                        .strip();
                                if (content.isEmpty()) {
                                    throw new LlmInvocationException(
                                            "Prompt translation returned an empty response");
                                }
                                return content;
                            },
                            ignored -> {
                            })
                    .value();

            log.debug("Translated video prompt to English: {}", translated);
            return translated;
        } catch (RuntimeException exception) {
            // Best effort only: translation must never block a video job.
            log.warn("Prompt translation failed ({}); submitting the original prompt",
                    exception.getMessage());
            return prompt;
        }
    }

    private static boolean isAscii(String prompt) {
        return !NON_ASCII.matcher(prompt).find();
    }
}

package com.hub.backend.modules.video;

import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the Chinese prompt rewrite that runs before a video job is submitted.
 *
 * <p>The contract worth protecting is not "translation works" but "translation never makes a
 * working request fail": every failure path has to fall back to the original prompt.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoPromptTranslatorTest {

    private static final CurrentUser CURRENT_USER = new CurrentUser(1L, 1L, "demo");
    private static final ProviderCredentials RELAY = new ProviderCredentials(
            "OPENAI_COMPATIBLE", "http://new-api:3000/v1", "relay-key", null, null, null);

    @Mock
    private LlmClient llmClient;

    @Mock
    private ModelUseResolver modelResolver;

    @Mock
    private UserRelayCredentialResolver relayCredentials;

    private VideoPromptTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new VideoPromptTranslator(llmClient, modelResolver, relayCredentials);

        when(modelResolver.resolveAllForSite(anyLong(), any(), any()))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        1L, "deepseek-v4.1-flash", "deepseek-v4.1-flash",
                        LlmModelEntity.TYPE_CHAT, true, false, null, false, null, null)));
        when(relayCredentials.resolveKeyOnly(CURRENT_USER, 1L)).thenReturn(RELAY);
    }

    @Test
    void leavesAnEnglishPromptAloneWithoutCallingTheModel() {
        String prompt = "a calico cat turning its head toward the camera, cinematic";

        String result = translator.toUpstreamPrompt(CURRENT_USER, prompt);

        assertEquals(prompt, result);
        verify(llmClient, never()).complete(any());
    }

    @Test
    void returnsThePromptUnchangedWhenBlank() {
        assertEquals("", translator.toUpstreamPrompt(CURRENT_USER, ""));
        verify(llmClient, never()).complete(any());
    }

    @Test
    void translatesAChinesePrompt() {
        when(llmClient.complete(any())).thenReturn(completion(
                "A calico cat slowly turns its head toward the camera, cinematic lighting"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "橘猫慢慢转头看向镜头，电影感");

        assertEquals("A calico cat slowly turns its head toward the camera, cinematic lighting", result);
    }

    @Test
    void usesTheCallersRelayKey() {
        when(llmClient.complete(any())).thenReturn(completion("translated"));

        translator.toUpstreamPrompt(CURRENT_USER, "一只猫");

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals("relay-key", command.getValue().credentials().apiKey());
        assertEquals("deepseek-v4.1-flash", command.getValue().model());
    }

    @Test
    void fallsBackToTheOriginalPromptWhenTheModelFails() {
        when(llmClient.complete(any())).thenThrow(new LlmInvocationException("gateway is down"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "一只猫");

        assertEquals("一只猫", result);
    }

    @Test
    void retriesTheNextFallbackCandidateBeforeReturningTheOriginalPrompt() {
        when(modelResolver.resolveAllForSite(anyLong(), any(), any())).thenReturn(List.of(
                new ModelUseResolver.Resolved(1L, "primary", "primary-route",
                        LlmModelEntity.TYPE_CHAT, false, false, null, false, null, null),
                new ModelUseResolver.Resolved(null, "fallback", "fallback-route",
                        LlmModelEntity.TYPE_CHAT, false, false, null, false, null, null)));
        when(llmClient.complete(any()))
                .thenThrow(new LlmInvocationException("primary failed"))
                .thenReturn(completion("translated by fallback"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "一只猫");

        assertEquals("translated by fallback", result);
        ArgumentCaptor<LlmChatCommand> commands = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient, org.mockito.Mockito.times(2)).complete(commands.capture());
        assertEquals("primary-route", commands.getAllValues().get(0).model());
        assertEquals("fallback-route", commands.getAllValues().get(1).model());
    }

    @Test
    void fallsBackToTheOriginalPromptWhenTheAnswerIsEmpty() {
        when(llmClient.complete(any())).thenReturn(completion("   "));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "一只猫");

        assertEquals("一只猫", result);
    }

    @Test
    void fallsBackToTheOriginalPromptWhenRelayResolutionFails() {
        when(relayCredentials.resolveKeyOnly(CURRENT_USER, 1L))
                .thenThrow(new IllegalStateException("no relay key"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "一只猫");

        assertEquals("一只猫", result);
        verify(llmClient, never()).complete(any());
    }

    @Test
    void treatsPunctuationOnlyAsciiTextAsAlreadyEnglish() {
        when(llmClient.complete(any())).thenReturn(completion("unused"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "a dog, 4K, 60fps!");

        assertEquals("a dog, 4K, 60fps!", result);
        verify(llmClient, never()).complete(any());
    }

    @Test
    void detectsTheChineseFullStopEvenWithoutHanCharacters() {
        when(llmClient.complete(any())).thenReturn(completion("translated"));

        String result = translator.toUpstreamPrompt(CURRENT_USER, "a cat。");

        assertTrue(result.equals("translated"),
                "non-ASCII punctuation is a reliable signal that the prompt is not English");
    }

    private static LlmChatCompletion completion(String content) {
        return new LlmChatCompletion("id", "deepseek-v4.1-flash", content, "stop", LlmUsage.empty());
    }
}

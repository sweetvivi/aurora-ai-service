package com.hub.backend.modules.aicanvas;

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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs AI Canvas assistant and storyboard prompts through the configured chat model.
 */
@Component
public class AiCanvasModelCaller {

    private final LlmClient llmClient;
    private final ModelUseResolver modelResolver;
    private final UserRelayCredentialResolver relayCredentials;

    public AiCanvasModelCaller(LlmClient llmClient,
                               ModelUseResolver modelResolver,
                               UserRelayCredentialResolver relayCredentials) {
        this.llmClient = llmClient;
        this.modelResolver = modelResolver;
        this.relayCredentials = relayCredentials;
    }

    /**
     * Performs the paid-call gate before callers read or decode user media.
     */
    PreparedCompletion prepare(CurrentUser currentUser) {
        ProviderCredentials credentials = relayCredentials.resolve(currentUser);
        List<ModelUseResolver.Resolved> models = modelResolver.resolveAllForSite(
                currentUser.siteId(), null, LlmModelEntity.TYPE_CHAT);
        return new PreparedCompletion(models, credentials);
    }

    public String complete(CurrentUser currentUser,
                           String system,
                           List<LlmChatMessage> messages,
                           Integer maxTokens) {
        return complete(prepare(currentUser), system, messages, maxTokens);
    }

    String complete(PreparedCompletion prepared,
                    String system,
                    List<LlmChatMessage> messages,
                    Integer maxTokens) {
        List<LlmChatMessage> completeMessages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            completeMessages.add(LlmChatMessage.system(system.strip()));
        }
        completeMessages.addAll(messages);

        ModelFallbackInvoker.Result<String> result = ModelFallbackInvoker.invoke(
                prepared.models(),
                candidate -> {
                    String content = llmClient.complete(new LlmChatCommand(
                                    candidate.upstreamKey(),
                                    completeMessages,
                                    null,
                                    maxTokens,
                                    prepared.credentials()))
                            .contentOrEmpty()
                            .strip();
                    if (content.isEmpty()) {
                        throw new LlmInvocationException("模型没有返回任何内容，请重试");
                    }
                    return content;
                },
                ignored -> {
                });
        return result.value();
    }

    public String complete(CurrentUser currentUser, String system, String userPrompt, Integer maxTokens) {
        return complete(currentUser, system, List.of(LlmChatMessage.user(userPrompt)), maxTokens);
    }

    String complete(PreparedCompletion prepared,
                    String system,
                    String userPrompt,
                    Integer maxTokens) {
        return complete(prepared, system, List.of(LlmChatMessage.user(userPrompt)), maxTokens);
    }

    record PreparedCompletion(List<ModelUseResolver.Resolved> models,
                              ProviderCredentials credentials) {

        PreparedCompletion {
            models = models == null ? List.of() : List.copyOf(models);
        }

        ModelUseResolver.Resolved model() {
            return models.get(0);
        }
    }
}

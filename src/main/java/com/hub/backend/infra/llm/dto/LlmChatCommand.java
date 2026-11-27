package com.hub.backend.infra.llm.dto;

import com.hub.backend.infra.llm.ProviderCredentials;

import java.util.List;

/**
 * Provider independent description of one chat completion call.
 *
 * @param model              upstream model key resolved by the caller
 * @param messages           conversation context, oldest first
 * @param temperature        optional sampling temperature
 * @param maxTokens          optional upper bound of generated tokens
 * @param credentials        supplier credentials and endpoint overrides, {@code null} to use the service
 *                           defaults from {@code app.llm.*}
 * @param tools              function tools the model may call, empty to disable function calling
 * @param builtInTools       provider built-in tool types (e.g. {@code web_search}), separate from function tools
 * @param thinking           whether the user asked for reasoning on this turn
 * @param thinkingParameter  OpenAI-compatible request field name; when non-null the adapter always
 *                           writes the field (on or off value) so default-on upstreams can be forced off
 * @param thinkingOnValue    raw on-value from catalogue config; blank means boolean {@code true}
 * @param thinkingOffValue   raw off-value from catalogue config; blank means boolean {@code false}
 */
public record LlmChatCommand(
        String model,
        List<LlmChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        ProviderCredentials credentials,
        List<LlmToolDefinition> tools,
        List<String> builtInTools,
        boolean thinking,
        String thinkingParameter,
        String thinkingOnValue,
        String thinkingOffValue
) {

    /** Built-in OpenAI-compatible web search tool type. */
    public static final String BUILTIN_WEB_SEARCH = "web_search";

    public LlmChatCommand {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        builtInTools = builtInTools == null ? List.of() : List.copyOf(builtInTools);
    }

    public LlmChatCommand(String model,
                          List<LlmChatMessage> messages,
                          Double temperature,
                          Integer maxTokens,
                          ProviderCredentials credentials,
                          List<LlmToolDefinition> tools) {
        this(model, messages, temperature, maxTokens, credentials, tools, List.of(), false, null, null, null);
    }

    public LlmChatCommand(String model,
                          List<LlmChatMessage> messages,
                          Double temperature,
                          Integer maxTokens,
                          ProviderCredentials credentials) {
        this(model, messages, temperature, maxTokens, credentials, List.of(), List.of(), false, null, null, null);
    }

    public static LlmChatCommand of(String model, List<LlmChatMessage> messages) {
        return new LlmChatCommand(model, messages, null, null, null, List.of(), List.of(), false, null, null, null);
    }

    public LlmChatCommand withTools(List<LlmToolDefinition> declaredTools) {
        return new LlmChatCommand(model, messages, temperature, maxTokens, credentials, declaredTools,
                builtInTools, thinking, thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    public LlmChatCommand withBuiltInTools(List<String> nextBuiltInTools) {
        return new LlmChatCommand(model, messages, temperature, maxTokens, credentials, tools,
                nextBuiltInTools, thinking, thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    public LlmChatCommand withMessages(List<LlmChatMessage> nextMessages) {
        return new LlmChatCommand(model, nextMessages, temperature, maxTokens, credentials, tools,
                builtInTools, thinking, thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    /** True when function tools or built-in tools are offered. */
    public boolean hasTools() {
        return !tools.isEmpty() || !builtInTools.isEmpty();
    }

    public boolean hasBuiltInWebSearch() {
        return builtInTools.stream().anyMatch(BUILTIN_WEB_SEARCH::equals);
    }
}

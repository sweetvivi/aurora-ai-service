package com.hub.backend.infra.llm.dto;

import java.util.List;

/**
 * One message of the upstream chat payload.
 *
 * <p>Three shapes exist beyond plain text: an assistant turn that <em>requests</em> tool calls
 * ({@code toolCalls} populated, {@code content} usually {@code null}), a {@code tool} turn carrying
 * one result back ({@code toolCallId} pointing at the request), and ordinary user/system turns.</p>
 *
 * @param role       provider-neutral role
 * @param content    text content, or an empty string for an image-only turn
 * @param images     binary image inputs carried as protocol-native multimodal parts
 * @param toolCalls  calls the assistant asked for, empty for every other role
 * @param toolCallId id of the call this message answers, only for the {@code tool} role
 */
public record LlmChatMessage(String role,
                             String content,
                             List<LlmImageAttachment> images,
                             List<LlmToolCall> toolCalls,
                             String toolCallId) {

    /** Role of a system instruction. */
    public static final String ROLE_SYSTEM = "system";

    /** Role of the end user. */
    public static final String ROLE_USER = "user";

    /** Role of the model. */
    public static final String ROLE_ASSISTANT = "assistant";

    /** Role of a tool result fed back to the model. */
    public static final String ROLE_TOOL = "tool";

    public LlmChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmChatMessage(String role, String content) {
        this(role, content, List.of(), List.of(), null);
    }

    public LlmChatMessage(String role, String content, List<LlmImageAttachment> images) {
        this(role, content, images, List.of(), null);
    }

    public static LlmChatMessage system(String content) {
        return new LlmChatMessage(ROLE_SYSTEM, content);
    }

    public static LlmChatMessage user(String content) {
        return new LlmChatMessage(ROLE_USER, content);
    }

    public static LlmChatMessage assistant(String content) {
        return new LlmChatMessage(ROLE_ASSISTANT, content);
    }

    public static LlmChatMessage user(String content, List<LlmImageAttachment> images) {
        return new LlmChatMessage(ROLE_USER, content, images);
    }

    /** An assistant turn that asked for tools and produced no visible text. */
    public static LlmChatMessage assistantToolCalls(List<LlmToolCall> calls) {
        return new LlmChatMessage(ROLE_ASSISTANT, null, List.of(), calls, null);
    }

    /** The output of one tool, addressed back to the call that requested it. */
    public static LlmChatMessage toolResult(String toolCallId, String content) {
        return new LlmChatMessage(ROLE_TOOL, content, List.of(), List.of(), toolCallId);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean isToolResult() {
        return ROLE_TOOL.equals(role);
    }
}

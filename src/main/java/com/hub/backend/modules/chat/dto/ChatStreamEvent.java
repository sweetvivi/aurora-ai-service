package com.hub.backend.modules.chat.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One frame of the SSE chat stream.
 *
 * <p>Event names follow the vocabulary agreed for the platform so that a client can be written
 * once and reused across endpoints.</p>
 */
public record ChatStreamEvent(String event, Map<String, Object> data) {

    /** The exchange has been created and persisted. */
    public static final String EVENT_CREATED = "message.created";

    /** An increment of the visible answer. */
    public static final String EVENT_DELTA = "message.delta";

    /** An increment of the reasoning trace, when the model exposes one. */
    public static final String EVENT_THINKING = "message.thinking";

    /** The answer is complete. */
    public static final String EVENT_COMPLETED = "message.completed";

    /** The answer failed; the stream ends after this frame. */
    public static final String EVENT_FAILED = "message.failed";

    /** The generation was stopped; the stream ends after this frame. */
    public static final String EVENT_CANCELLED = "message.cancelled";

    /** The model asked to run a tool; emitted before the tool executes. */
    public static final String EVENT_TOOL_CALL = "tool.call";

    /** A tool finished. Carries its text output plus optional structured metadata. */
    public static final String EVENT_TOOL_RESULT = "tool.result";

    /**
     * @param requestId id a client passes to {@code POST /api/v1/chat/requests/{requestId}/cancel}
     */
    public static ChatStreamEvent created(String requestId, Long conversationId, Long messageId, String model) {
        return created(requestId, conversationId, messageId, null, null, model);
    }

    /**
     * @param userMessageId      persisted user turn for this exchange (null on pure regenerate)
     * @param assistantTurnGroup turn group the upcoming assistant answer will join (regenerate)
     */
    public static ChatStreamEvent created(String requestId, Long conversationId, Long messageId,
                                          Long userMessageId, Long assistantTurnGroup, String model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("conversationId", conversationId);
        payload.put("messageId", messageId);
        if (userMessageId != null) {
            payload.put("userMessageId", userMessageId);
        }
        if (assistantTurnGroup != null) {
            payload.put("assistantTurnGroupId", assistantTurnGroup);
        }
        payload.put("model", model);
        return new ChatStreamEvent(EVENT_CREATED, payload);
    }

    public static ChatStreamEvent completed(String content, Long messageId, Long turnGroupId, int version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }
        if (turnGroupId != null) {
            payload.put("turnGroupId", turnGroupId);
        }
        payload.put("version", version);
        return new ChatStreamEvent(EVENT_COMPLETED, payload);
    }

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent(EVENT_DELTA, single("content", content));
    }

    public static ChatStreamEvent thinking(String content) {
        return new ChatStreamEvent(EVENT_THINKING, single("content", content));
    }

    public static ChatStreamEvent completed(String content) {
        return completed(content, null, null, 1);
    }

    public static ChatStreamEvent failed(String message) {
        return failed(message, null, null, 1);
    }

    /**
     * @param messageId  persisted FAILED assistant row, when the exchange was already written
     * @param turnGroupId turn group of that row (for the client 1/N pager)
     */
    public static ChatStreamEvent failed(String message, Long messageId, Long turnGroupId, int version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }
        if (turnGroupId != null) {
            payload.put("turnGroupId", turnGroupId);
        }
        payload.put("version", version);
        return new ChatStreamEvent(EVENT_FAILED, payload);
    }

    /**
     * @param content the partial answer produced before the stop, so the client keeps what it saw
     */
    public static ChatStreamEvent cancelled(String content) {
        return new ChatStreamEvent(EVENT_CANCELLED, single("content", content));
    }

    /**
     * @param id        call id assigned by the model, echoed back on the tool result
     * @param name      tool the model picked
     * @param arguments raw JSON arguments produced by the model
     */
    public static ChatStreamEvent toolCall(String id, String name, String arguments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", name);
        payload.put("arguments", arguments);
        return new ChatStreamEvent(EVENT_TOOL_CALL, payload);
    }

    /**
     * @param meta structured extras the UI needs but the model does not, such as generated image
     *             urls; omitted from the frame when empty
     */
    public static ChatStreamEvent toolResult(String id, String name, boolean success,
                                             String content, Map<String, Object> meta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", name);
        payload.put("success", success);
        payload.put("content", content);
        if (meta != null && !meta.isEmpty()) {
            payload.put("meta", meta);
        }
        return new ChatStreamEvent(EVENT_TOOL_RESULT, payload);
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }
}

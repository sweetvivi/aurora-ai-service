package com.hub.backend.modules.chat;

import com.hub.backend.modules.chat.dto.ChatStreamEvent;

/**
 * Transport neutral sink for streamed chat frames.
 *
 * <p>Keeping the service independent from {@code SseEmitter} makes the streaming path unit
 * testable without a servlet container.</p>
 */
@FunctionalInterface
public interface ChatEventPublisher {

    void publish(ChatStreamEvent event);
}

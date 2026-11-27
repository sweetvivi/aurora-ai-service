package com.hub.backend.modules.chat;

/**
 * Internal signal that a streaming generation was stopped.
 *
 * <p>Thrown from inside the chunk callback. That matters: the callbacks run inside the gateway
 * adapter's try-with-resources block, so letting this exception unwind closes the upstream response
 * stream and actually stops the model, instead of merely ignoring the remaining tokens.</p>
 */
class ChatGenerationCancelledException extends RuntimeException {

    ChatGenerationCancelledException(String requestId) {
        super("Generation '%s' was cancelled".formatted(requestId));
    }
}

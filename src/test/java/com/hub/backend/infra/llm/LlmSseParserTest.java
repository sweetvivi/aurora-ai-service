package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSseParserTest {

    private final LlmSseParser parser = new LlmSseParser(new ObjectMapper());

    @Test
    void ignoresLinesThatCarryNoPayload() {
        assertTrue(parser.parseLine(null).isEmpty());
        assertTrue(parser.parseLine("").isEmpty());
        assertTrue(parser.parseLine("   ").isEmpty());
        assertTrue(parser.parseLine(": keep-alive").isEmpty());
        assertTrue(parser.parseLine("event: message.delta").isEmpty());
        assertTrue(parser.parseLine("data:").isEmpty());
    }

    @Test
    void decodesVisibleContentDelta() {
        String frame = """
                data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null,"index":0}],\
                "created":1791967720,"id":"abc","model":"deepseek-v4.1-flash",\
                "object":"chat.completion.chunk","usage":null}""";

        LlmStreamChunk chunk = parser.parseLine(frame).orElseThrow();

        assertEquals("abc", chunk.id());
        assertEquals("Hello", chunk.contentDelta());
        assertNull(chunk.reasoningDelta());
        assertFalse(chunk.done());
        assertTrue(chunk.hasContent());
    }

    @Test
    void decodesReasoningDeltaSeparatelyFromContent() {
        String frame = """
                data: {"choices":[{"delta":{"reasoning_content":"He"},"finish_reason":null,"index":0}],\
                "id":"abc","object":"chat.completion.chunk"}""";

        LlmStreamChunk chunk = parser.parseLine(frame).orElseThrow();

        assertEquals("He", chunk.reasoningDelta());
        assertNull(chunk.contentDelta());
        assertTrue(chunk.hasReasoning());
        assertFalse(chunk.hasContent());
    }

    @Test
    void decodesReasoningAliasUsedByGptStyleGateways() {
        String frame = """
                data: {"choices":[{"delta":{"reasoning":"step"},"finish_reason":null,"index":0}],\
                "id":"abc","object":"chat.completion.chunk"}""";

        LlmStreamChunk chunk = parser.parseLine(frame).orElseThrow();

        assertEquals("step", chunk.reasoningDelta());
        assertTrue(chunk.hasReasoning());
    }

    @Test
    void prefersReasoningContentWhenBothAliasesPresent() {
        String frame = """
                data: {"choices":[{"delta":{"reasoning_content":"a","reasoning":"b"}}],\
                "id":"abc"}""";

        LlmStreamChunk chunk = parser.parseLine(frame).orElseThrow();

        assertEquals("a", chunk.reasoningDelta());
    }

    @Test
    void decodesFinishReason() {
        String frame = """
                data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}],"id":"abc"}""";

        LlmStreamChunk chunk = parser.parseLine(frame).orElseThrow();

        assertEquals("stop", chunk.finishReason());
        assertFalse(chunk.done());
    }

    @Test
    void recognisesDoneSentinel() {
        LlmStreamChunk chunk = parser.parseLine("data: [DONE]").orElseThrow();

        assertTrue(chunk.done());
        assertNull(chunk.contentDelta());
    }

    @Test
    void acceptsFrameWithoutSpaceAfterColon() {
        LlmStreamChunk chunk = parser
                .parseLine("data:{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}")
                .orElseThrow();

        assertEquals("x", chunk.contentDelta());
    }

    @Test
    void toleratesFrameWithoutChoices() {
        LlmStreamChunk chunk = parser.parseLine("""
                data: {"id":"abc","usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""")
                .orElseThrow();

        assertEquals("abc", chunk.id());
        assertNull(chunk.contentDelta());
        assertEquals(10, chunk.usage().promptTokens());
        assertEquals(2, chunk.usage().completionTokens());
        assertEquals(12, chunk.usage().totalTokens());
    }

    @Test
    void reportsMalformedPayload() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> parser.parseLine("data: {\"choices\": ["));

        assertTrue(exception.getMessage().contains("stream frame"));
    }
}

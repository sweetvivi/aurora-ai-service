package com.hub.backend.modules.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.agent.dto.AgentToolCallRecord;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentJsonColumnCodecTest {

    private final AgentJsonColumnCodec codec = new AgentJsonColumnCodec(new ObjectMapper());

    @Test
    void roundTripsToolCallRecordsIncludingStructuredMeta() {
        AgentToolCallRecord record = new AgentToolCallRecord(
                "call-1", "generate_image", true, "{\"images\":[]}",
                Map.of("images", List.of(Map.of("url", "/api/v1/files/9/content", "fileId", 9))));

        List<AgentToolCallRecord> decoded = codec.decode(
                codec.encode(List.of(record)), AgentToolCallRecord.class);

        assertEquals(1, decoded.size());
        assertEquals("call-1", decoded.get(0).id());
        assertEquals("generate_image", decoded.get(0).name());
        assertTrue(decoded.get(0).success());
        assertEquals(List.of(Map.of("url", "/api/v1/files/9/content", "fileId", 9)),
                decoded.get(0).meta().get("images"));
    }

    @Test
    void roundTripsAttachmentReferences() {
        ChatAttachmentRef ref = new ChatAttachmentRef(
                5L, "image/png", "shirt.png", 20480, "/api/v1/files/5/content");

        List<ChatAttachmentRef> decoded = codec.decode(
                codec.encode(List.of(ref)), ChatAttachmentRef.class);

        assertEquals(1, decoded.size());
        assertEquals(5L, decoded.get(0).id().longValue());
        assertEquals("image/png", decoded.get(0).mimeType());
        assertEquals("shirt.png", decoded.get(0).originalName());
        assertEquals(20480, decoded.get(0).sizeBytes());
    }

    @Test
    void emptyOrMissingInputIsStoredAsNull() {
        assertNull(codec.encode(List.of()));
        assertNull(codec.encode(null));
    }

    @Test
    void blankStoredValueDecodesToAnEmptyList() {
        assertTrue(codec.decode(null, ChatAttachmentRef.class).isEmpty());
        assertTrue(codec.decode("", ChatAttachmentRef.class).isEmpty());
        assertTrue(codec.decode("   ", ChatAttachmentRef.class).isEmpty());
    }

    @Test
    void corruptStoredValueDegradesInsteadOfFailingTheWholeHistory() {
        assertTrue(codec.decode("[ this is not json", ChatAttachmentRef.class).isEmpty());
        assertTrue(codec.decode("{\"unexpected\":\"object\"}", AgentToolCallRecord.class).isEmpty());
    }
}

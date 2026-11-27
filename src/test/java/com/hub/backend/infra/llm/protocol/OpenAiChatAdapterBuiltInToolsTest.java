package com.hub.backend.infra.llm.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatAdapterBuiltInToolsTest {

    private final OpenAiChatAdapter adapter = new OpenAiChatAdapter(ChatProtocol.OPENAI_COMPATIBLE);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialisesNativeWebSearchAlongsideFunctionTools() throws Exception {
        LlmChatCommand command = new LlmChatCommand(
                "gpt-5.5",
                List.of(LlmChatMessage.user("今日上证？")),
                null,
                null,
                null,
                List.of(new LlmToolDefinition(
                        "generate_markdown", "md", Map.of("type", "object"))),
                List.of(LlmChatCommand.BUILTIN_WEB_SEARCH),
                false,
                null,
                null,
                null);

        JsonNode root = mapper.readTree(adapter.body(command, mapper, false));
        JsonNode tools = root.path("tools");
        assertEquals(2, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("generate_markdown", tools.get(0).path("function").path("name").asText());
        assertEquals("web_search", tools.get(1).path("type").asText());
        assertTrue(tools.get(1).path("function").isMissingNode());
        assertEquals("auto", root.path("tool_choice").asText());
    }

    @Test
    void serialisesWebSearchAloneWhenNoFunctionTools() throws Exception {
        LlmChatCommand command = new LlmChatCommand(
                "gpt-5.5",
                List.of(LlmChatMessage.user("news")),
                null,
                null,
                null,
                List.of(),
                List.of(LlmChatCommand.BUILTIN_WEB_SEARCH),
                false,
                null,
                null,
                null);

        JsonNode root = mapper.readTree(adapter.body(command, mapper, false));
        assertEquals(1, root.path("tools").size());
        assertEquals("web_search", root.path("tools").get(0).path("type").asText());
    }
}

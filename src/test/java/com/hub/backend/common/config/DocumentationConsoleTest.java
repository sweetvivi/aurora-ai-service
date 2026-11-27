package com.hub.backend.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated documentation console has to stay reachable, has to be the only one, and has to
 * remain callable.
 *
 * <p>Each half has broken at least once in a way no other test would notice. The console's paths are
 * served by static resources rather than by a controller, so nothing links them to the application.
 * The declared security scheme used to be an {@code apiKey} header named {@code Token} that this
 * service never reads, which made every Authorize request fail with a 401 while the document itself
 * still looked correct. Swagger UI used to be served alongside Knife4j — and the first attempt to
 * drop it removed {@code /v3/api-docs/swagger-config} as well, which is the endpoint Knife4j uses to
 * find the document: {@code /doc.html} kept answering {@code 200} while everything behind it 404'd,
 * so the console opened blank.</p>
 *
 * <p>Hence {@link #theConsoleCanActuallyBootstrapItsDocument()}: it walks the chain the browser
 * walks, instead of trusting the one URL an operator is most likely to check by hand.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentationConsoleTest {

    /** Where Knife4j's bundle looks for the document pointer; not configurable on our side. */
    private static final String SWAGGER_CONFIG_URL = "/v3/api-docs/swagger-config";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void servesTheKnife4jConsole() throws Exception {
        mockMvc.perform(get("/doc.html"))
                .andExpect(status().isOk());
    }

    @Test
    void theConsoleCanActuallyBootstrapItsDocument() throws Exception {
        // step 1: the pointer Knife4j fetches first
        String bootstrap = mockMvc.perform(get(SWAGGER_CONFIG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString();

        // step 2: the document that pointer names, followed exactly as the console would
        String documentUrl = objectMapper.readTree(bootstrap).get("url").asText();
        mockMvc.perform(get(documentUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/models']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }

    @Test
    void doesNotServeASecondConsole() throws Exception {
        // Knife4j is the documented way in. If Swagger UI comes back, it comes back with its own
        // view of the security scheme, and the fix applied to one console silently misses the other.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishesTheDocumentWithoutTheConsolePlumbing() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chat/completions']").exists())
                // the bootstrap endpoint is plumbing, not part of the contract
                .andExpect(jsonPath("$.paths['" + SWAGGER_CONFIG_URL + "']").doesNotExist());
    }

    @Test
    void declaresABearerSchemeSoTheConsoleCanActuallyAuthorise() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                // the header this service never read must not come back
                .andExpect(jsonPath("$.components.securitySchemes.Token").doesNotExist())
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }
}

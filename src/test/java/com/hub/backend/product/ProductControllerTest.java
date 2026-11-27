package com.hub.backend.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void clearProducts() {
        productRepository.deleteAll();
    }

    @Test
    void createsAndReadsProduct() throws Exception {
        String body = "{\"name\":\"Keyboard\",\"price\":199.90}";
        String location = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/products/[0-9]+")))
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(199.90));
    }

    @Test
    void listsProductsInIdOrder() throws Exception {
        createProduct("Keyboard", "199.90");
        createProduct("Mouse", "49.90");

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Keyboard"))
                .andExpect(jsonPath("$[0].price").value(199.90))
                .andExpect(jsonPath("$[1].name").value("Mouse"))
                .andExpect(jsonPath("$[1].price").value(49.90));
    }

    @Test
    void updatesProduct() throws Exception {
        String location = createProduct("Keyboard", "199.90");

        mockMvc.perform(put(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mechanical Keyboard\",\"price\":299.90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(299.90));

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(299.90));
    }

    @Test
    void deletesProductAndReturns404AfterDeletion() throws Exception {
        String location = createProduct("Keyboard", "199.90");

        mockMvc.perform(delete(location))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get(location))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value(location));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void returns404ForUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/products/999999999"));
    }

    @Test
    void returns404WhenUpdatingOrDeletingUnknownProduct() throws Exception {
        mockMvc.perform(put("/api/products/999999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":199.90}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(delete("/api/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsInvalidProduct() throws Exception {
        String body = "{\"name\":\"\",\"price\":-1}";

        String response = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.price").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode error = objectMapper.readTree(response);
        assertEquals(400, error.get("status").asInt());
        assertEquals(0, productRepository.count());
    }

    @Test
    void requiresProductNameAndPrice() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.price").exists());
    }

    @Test
    void rejectsValuesOutsideDatabaseLimits() throws Exception {
        String name = "N".repeat(256);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProductRequest(name, new BigDecimal("1.234")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.price").exists());

        assertEquals(0, productRepository.count());
    }

    @Test
    void rejectsInvalidUpdateWithoutChangingProduct() throws Exception {
        String location = createProduct("Keyboard", "199.90");

        mockMvc.perform(put(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.price").exists());

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.price").value(199.90));
    }

    @Test
    void exposesHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.info.title").isString())
                .andExpect(jsonPath("$.paths['/api/products'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/products'].post.requestBody").exists())
                .andExpect(jsonPath("$.paths['/api/products/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/products/{id}'].delete").exists())
                .andExpect(jsonPath("$.components.schemas.ProductRequest").exists());
    }

    private String createProduct(String name, String price) throws Exception {
        return mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductRequest(name, new BigDecimal(price)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
    }
}

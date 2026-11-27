package com.hub.backend.modules.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBrandKeysTest {

    @Test
    void slugsAsciiCompanyNames() {
        assertEquals("deepseek", ModelBrandKeys.familyKeyOf("DeepSeek"));
        assertEquals("gpt-image", ModelBrandKeys.familyKeyOf("GPT Image"));
    }

    @Test
    void mapsBlankAndCjkOnlyToOther() {
        assertEquals("other", ModelBrandKeys.familyKeyOf(null));
        assertEquals("other", ModelBrandKeys.familyKeyOf("  "));
        assertEquals("other", ModelBrandKeys.familyKeyOf("可灵"));
    }

    @Test
    void validatesBrandKeys() {
        assertTrue(ModelBrandKeys.isValidBrandKey("kling"));
        assertTrue(ModelBrandKeys.isValidBrandKey("seedance-pro"));
        assertFalse(ModelBrandKeys.isValidBrandKey("other"));
        assertFalse(ModelBrandKeys.isValidBrandKey("Kling"));
        assertFalse(ModelBrandKeys.isValidBrandKey(""));
        assertFalse(ModelBrandKeys.isValidBrandKey("-kling"));
    }
}

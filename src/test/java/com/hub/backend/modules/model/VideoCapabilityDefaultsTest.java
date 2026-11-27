package com.hub.backend.modules.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCapabilityDefaultsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void picksKnownVendorLimits() {
        assertEquals(14, VideoCapabilityDefaults.maxReferenceImages("grok-imagine-video-1.5"));
        assertEquals(14, VideoCapabilityDefaults.maxReferenceImages("yunwu/grok-imagine-video-1.5-lite"));
        assertEquals(7, VideoCapabilityDefaults.maxReferenceImages("grok-imagine-video"));
        assertEquals(4, VideoCapabilityDefaults.maxReferenceImages("seedance-2.0"));
        assertEquals(4, VideoCapabilityDefaults.maxReferenceImages("sora"));
    }

    @Test
    void capabilitiesJsonCarriesMaxReferenceImagesAndModes() throws Exception {
        JsonNode grok15 = objectMapper.readTree(
                VideoCapabilityDefaults.capabilitiesJson("grok-imagine-video-1.5"));
        assertEquals(14, grok15.path("maxReferenceImages").asInt());
        assertTrue(grok15.path("modes").toString().contains("reference2video"));

        JsonNode generic = objectMapper.readTree(
                VideoCapabilityDefaults.capabilitiesJson("seedance-2.0"));
        assertEquals(4, generic.path("maxReferenceImages").asInt());
        assertTrue(generic.path("modes").toString().contains("image2video"));
    }
}

package com.hub.backend.infra.llm.dto;

import com.hub.backend.infra.llm.ProviderCredentials;

/**
 * One image generation request.
 *
 * @param model       image model key
 * @param prompt      what to draw
 * @param size        {@code WxH} or {@code auto}, {@code null} to let the gateway decide
 * @param quality     {@code low} / {@code medium} / {@code high} / {@code auto}, {@code null} for the default
 * @param count       how many images to produce
 * @param credentials supplier credentials and endpoint overrides, {@code null} to use the service
 *                    defaults from {@code app.llm.*}
 */
public record ImageGenerationCommand(String model, String prompt, String size, String quality, int count,
                                     ProviderCredentials credentials) {
}

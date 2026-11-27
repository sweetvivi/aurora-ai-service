package com.hub.backend.infra.llm.dto;

/**
 * One model advertised by an upstream supplier.
 *
 * @param id             model key to use in requests
 * @param ownedBy        who publishes it, may be {@code null}
 * @param createdEpochSecond when the gateway says it was created, may be {@code null}
 */
public record UpstreamModel(String id, String ownedBy, Long createdEpochSecond) {
}

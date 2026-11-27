package com.hub.backend.infra.llm.dto;

/**
 * Acknowledgement from a supplier that a video job was accepted.
 *
 * @param taskId supplier side identifier used by {@link VideoGenerationStatus#upstreamTaskId()}
 */
public record UpstreamVideoTask(String taskId) {
}

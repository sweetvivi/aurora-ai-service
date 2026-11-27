package com.hub.backend.infra.llm.video;

import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.UpstreamVideoStatus;
import com.hub.backend.infra.llm.dto.UpstreamVideoTask;
import com.hub.backend.infra.llm.dto.VideoGenerationCommand;

/**
 * Port for asynchronous video generation.
 *
 * <p>Video is not a single request: a supplier accepts a job and answers with an id, which is
 * polled until it reaches a terminal state. Splitting the two calls into one port keeps the module
 * free of supplier specifics, in the same way {@code ImageGenerationClient} does for stills.</p>
 */
public interface VideoGenerationClient {

    /**
     * Submits a job.
     *
     * @throws com.hub.backend.common.exception.LlmInvocationException when the supplier is not
     *         configured, rejects the request, or cannot be reached
     */
    UpstreamVideoTask submit(VideoGenerationCommand command);

    /**
     * Reads the current state of a previously submitted job.
     */
    UpstreamVideoStatus poll(ProviderCredentials credentials, String upstreamTaskId);
}

package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Answer of one document question.
 *
 * @param answer    plain text answer
 * @param truncated whether the model only saw an excerpt of a very long document
 * @param modelKey  public key of the model that actually answered; equals the requested key unless
 *                  that model had been retired and the site default took over
 * @param modelName display name of {@code modelKey}, for the label under the answer
 */
@Schema(name = "DocumentChatResponse", description = "文档问答的回答")
public record DocumentChatResponse(String answer, boolean truncated,
                                   String modelKey, String modelName) {
}

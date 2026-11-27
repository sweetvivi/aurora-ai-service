package com.hub.backend.modules.admin.operations;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of an admin operation that only reports success.
 *
 * @param ok      whether the operation was applied
 * @param warning something the operator should know about even though the call succeeded — a
 *                secondary effect that did not complete. Omitted when there is nothing to say, so
 *                every other endpoint using this record keeps its {@code {"ok":true}} shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminOperationResponse(boolean ok, String warning) {

    public AdminOperationResponse(boolean ok) {
        this(ok, null);
    }
}
